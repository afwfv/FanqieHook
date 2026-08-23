package dev.operit.fanqiehook.web

import dev.operit.fanqiehook.support.HookSupport
import dev.operit.fanqiehook.support.Reflect
import org.json.JSONObject

/**
 * Legado-compatible book source API running inside the host process.
 *
 * Calls the app's own RPC facades through reflection (ro4.b / ro4.f), so requests
 * carry the user's real session — identical to what the MK module does. Response
 * objects are the app's own RPC models; they are serialised by their toString()
 * via JSONObject wrapper where no Gson is available.
 *
 * Content decryption chain (from MK DragonService, verified against v73332):
 *   FullRequest(bookId, itemId, keyRegisterTs) → ro4.f.k() → ItemContent
 *   → decryptContent(): key from w85.i.o(userId, keyVersion) (or DecryptKey plain)
 *   → NsReaderServiceApi.IMPL.readerChapterService().r(...) / reader.utils.o.b(...)
 */
object BookApi {

    private const val TAG = "BookApi"

    // RPC facade classes (obfuscated names drift between versions — resolved via
    // DexKit method signatures at first use, with v73332 names as fallback).
    private const val FACADE_B_FALLBACK = "ro4.b"
    private const val FACADE_F_FALLBACK = "ro4.f"

    @Volatile private var facadeB: String? = null
    @Volatile private var facadeF: String? = null

    /**
     * Resolve the RPC facade B (search/detail/catalog/shelf/audio). DexKit matches
     * the *interface* declaring e.g. bookDetailRxJava(BookDetailRequest); the actual
     * callable entry is the enclosing class whose static methods take the same
     * request types — so strip the `$inner` suffix when present.
     */
    private fun facadeB(): String {
        facadeB?.let { return it }
        val candidates = HookSupport.classResolver?.findClassesByMethodSignature(
            paramTypeNames = listOf("com.dragon.read.rpc.model.BookDetailRequest"),
        ) ?: emptyList()
        HookSupport.log?.info("[$TAG] RPC facadeB 候选: $candidates")
        val result = candidates.firstOrNull { it != FACADE_B_FALLBACK }
            ?.substringBefore('$')          // qa6.c$a → qa6.c (static entry class)
            ?: FACADE_B_FALLBACK
        facadeB = result
        HookSupport.log?.info("[$TAG] RPC facadeB 定位: $result")
        return result
    }

    /**
     * Resolve the RPC facade F (full content) via `method(FullRequest)`.
     */
    private fun facadeF(): String {
        facadeF?.let { return it }
        val candidates = HookSupport.classResolver?.findClassesByMethodSignature(
            paramTypeNames = listOf("com.dragon.read.rpc.model.FullRequest"),
        ) ?: emptyList()
        HookSupport.log?.info("[$TAG] RPC facadeF 候选: $candidates")
        val result = candidates.firstOrNull { it != FACADE_F_FALLBACK }
            ?.substringBefore('$')
            ?: FACADE_F_FALLBACK
        facadeF = result
        HookSupport.log?.info("[$TAG] RPC facadeF 定位: $result")
        return result
    }

    // Request model classes (stable names).
    private const val SEARCH_REQ = "com.dragon.read.rpc.model.GetSearchPageRequest"
    private const val SEARCH_TAB_TYPE = "com.dragon.read.rpc.model.SearchTabType"
    private const val SEARCH_SOURCE = "com.dragon.read.rpc.model.SearchSource"
    private const val DETAIL_REQ = "com.dragon.read.rpc.model.BookDetailRequest"
    private const val DETAIL_SOURCE = "com.dragon.read.rpc.model.DetailSource"
    private const val MULTI_DETAIL_REQ = "com.dragon.read.rpc.model.MBookDetailRequest"
    private const val DIRECTORY_REQ = "com.dragon.read.rpc.model.GetDirectoryForItemIdRequest"
    private const val DIRECTORY_REQ_V2 = "com.dragon.read.rpc.model.GetDirectoryForInfoRequest"
    private const val FULL_REQ = "com.dragon.read.rpc.model.FullRequest"
    private const val NOVEL_TEXT_TYPE = "com.dragon.read.rpc.model.NovelTextType"
    private const val BATCH_FULL_REQ = "readersaas.com.dragon.read.saas.rpc.model.BatchFullRequest"
    private const val SHELF_INFO_REQ = "com.dragon.read.rpc.model.GetBookShelfInfoRequest"
    private const val ADD_SHELF_REQ = "com.dragon.read.rpc.model.AddBookShelfInfoRequest"
    private const val ADD_SHELF_SOURCE = "com.dragon.read.rpc.model.AddBookShelfSource"
    private const val READING_BOOK_TYPE = "com.dragon.read.rpc.model.ReadingBookType"
    private const val SHELF_IDENTIFY = "com.dragon.read.rpc.model.BookShelfIdentifyData"
    private const val AUDIO_URL_REQ = "com.dragon.read.rpc.model.AudioPlayURLRequest"
    private const val AUDIO_URL_REQ_TYPE = "com.dragon.read.rpc.model.AudioPlayUrlReqType"
    private const val TONE_QUALITY = "com.dragon.read.rpc.model.ToneQuality"

    // Decryption helpers (obfuscated where marked).
    private const val U56_A_FALLBACK = "u56.a"                     // batch facade (v73332)
    private const val READER_API = "com.dragon.read.component.biz.api.NsReaderServiceApi"
    private const val DECRYPT_KEY = "com.dragon.read.reader.DecryptKey"

    // ── 解密链路（热更后，全部在 chapter service 实例上，运行时验证） ─────────
    //
    // service = NsReaderServiceApi.IMPL.readerChapterService()  // reader.services.t
    //   a(String):Single<DecryptKey>   — 取内容密钥
    //   b(int, String):Single          — 按 keyVersion 取密钥（参数序与 a 相反）
    //   d(String):int                  — keyRegisterTs 获取器
    //   r(String, DecryptKey, boolean, String, String):String — 解密
    //
    // DecryptKey: f:boolean = 明文标志；static g = 明文 key 持有者（g.b() 取 key）。

    /** Chapter service 单例（com.dragon.read.reader.services.t 实例）。 */
    @Volatile private var chapterService: Any? = null

    private fun chapterService(): Any? {
        chapterService?.let { return it }
        val s = runCatching {
            Reflect.onClass(READER_API).field("IMPL").call("readerChapterService").get()
        }.getOrNull()
        if (s != null) {
            chapterService = s
            HookSupport.log?.info("[$TAG] chapter service 定位: ${s.javaClass.name}")
        } else {
            HookSupport.log?.warn("[$TAG] chapter service 不可达")
        }
        return s
    }

    /** 批量正文 facade：DexKit 按 BatchFullRequest 参数定位，v73332 名兜底。 */
    private fun batchFacade(): String {
        batchFacadeResolved?.let { return it }
        val candidates = HookSupport.classResolver?.findClassesByMethodSignature(
            paramTypeNames = listOf(BATCH_FULL_REQ),
        ) ?: emptyList()
        val result = candidates.firstOrNull()
            ?.substringBefore('$')
            ?: U56_A_FALLBACK
        batchFacadeResolved = result
        HookSupport.log?.info("[$TAG] RPC batchFacade 定位: $result")
        return result
    }

    @Volatile private var batchFacadeResolved: String? = null

    /** Route dispatcher. Returns a JSON string, or null for 404. */
    fun handle(uri: String, p: Map<String, String>): String? = try {
        when (uri) {
            "/reading/bookapi/search/tab" ->
                ok(search(p["query"] ?: "", int(p["page"], 1), int(p["count"], 10), int(p["tab_type"], 3)))
            "/reading/bookapi/detail" ->
                ok(getDetail(p["book_id"] ?: "", int(p["source"], 0), bool(p["withoutVideo"], false)))
            "/reading/bookapi/multi-detail" ->
                ok(getMultiDetail((p["book_id"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            "/reading/bookapi/directory/all_items" ->
                ok(getCatalog(p["book_id"] ?: ""))
            "/reading/reader/full" ->
                ok(getContent(p["book_id"] ?: "", p["item_id"] ?: "", int(p["novel_text_type"], 0)))
            "/reading/reader/batch_full" ->
                ok(getBatchContent(p["book_id"] ?: "",
                    (p["item_ids"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            "/reading/bookapi/bookshelf/info" ->
                ok(getBookShelfInfo())
            "/reading/bookapi/bookshelf/add" ->
                ok(addBookShelfInfo(p["book_id"] ?: "", p["type"] ?: "read"))
            "/reading/bookapi/audio/playurl" ->
                ok(getAudioPlayURL(p["book_id"] ?: "", p["item_id"] ?: "",
                    int(p["tone_quality"], 128), long(p["tone_id"], 0)))
            else -> null
        }
    } catch (t: Throwable) {
        JSONObject().put("error", "${t.javaClass.simpleName}: ${t.message}").toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RPC primitives
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Invoke a facade RPC. Method names drift across releases (i0/h/K → g/bookDetail/…),
     * so dispatch purely by the request parameter type: find the single static method
     * whose sole parameter accepts [request] and which returns an Rx type, then
     * blockingFirst() the returned observable.
     */
    private fun callFunction(facade: String, request: Any?, legacyName: String? = null): Any? {
        if (request == null) return null
        val cls = try {
            HookSupport.dragonLoader().loadClass(facade)
        } catch (t: Throwable) {
            HookSupport.log?.warn("[$TAG] facade 类不存在: $facade")
            return null
        }

        // Legacy path first: the v73332 obfuscated method name (still valid on
        // un-hotfixed builds where the facade is ro4.b). Must also match the
        // parameter type — the same short name may exist for a DIFFERENT request
        // on newer builds (ro4.b.h(BookDetailRequest) vs qa6.c.h(MBookDetailRequest)).
        if (legacyName != null) {
            val legacy = cls.declaredMethods.firstOrNull {
                it.name == legacyName && it.parameterCount == 1 &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes[0].isInstance(request)
            }
            if (legacy != null) {
                legacy.isAccessible = true
                return blockingResult(legacy.invoke(null, request), facade, legacy.name)
            }
        }

        // Version-agnostic path: static method taking the request type → Rx type.
        val method = cls.declaredMethods.firstOrNull { m ->
            m.parameterCount == 1 &&
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes[0].isInstance(request) &&
                isRxType(m.returnType)
        }
        if (method != null) {
            method.isAccessible = true
            return blockingResult(method.invoke(null, request), facade, method.name)
        }

        // Interface path (hot-fixed builds): the enclosing class only exposes a few
        // curated static entries; the full method set lives on the inner interface
        // (qa6.c$a). Obtain its instance via the static no-arg getter that returns
        // it (qa6.c.c()), then dispatch by parameter type on the instance.
        val interfaceCls = runCatching {
            HookSupport.dragonLoader().loadClass(facade + "\$a")
        }.getOrNull()
        if (interfaceCls != null) {
            val getter = cls.declaredMethods.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    interfaceCls.isAssignableFrom(m.returnType)
            }
            if (getter != null) {
                getter.isAccessible = true
                val instance = runCatching { getter.invoke(null) }.getOrNull()
                if (instance != null) {
                    val ifaceMethod = interfaceCls.methods.firstOrNull { m ->
                        m.parameterCount == 1 &&
                            m.parameterTypes[0].isInstance(request) &&
                            isRxType(m.returnType)
                    }
                    if (ifaceMethod != null) {
                        ifaceMethod.isAccessible = true
                        return blockingResult(ifaceMethod.invoke(instance, request), facade, ifaceMethod.name)
                    }
                }
            }
        }

        HookSupport.log?.warn("[$TAG] $facade 中未找到接收 ${request.javaClass.simpleName} 的方法（static/接口路径均失败）")
        return null
    }

    private fun isRxType(type: Class<*>): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            val n = c.name
            if (n.startsWith("io.reactivex.") || n.startsWith("rx.")) return true
            c = c.superclass
        }
        // Also accept interfaces extending Rx types.
        return type.interfaces.any { isRxType(it) }
    }

    private fun blockingResult(observable: Any?, facade: String, methodName: String): Any? {
        if (observable == null) {
            HookSupport.log?.warn("[$TAG] $facade.$methodName() 返回 null")
            return null
        }
        val result = Reflect.on(observable).call("blockingFirst").get()
        if (result == null) {
            HookSupport.log?.warn("[$TAG] $facade.$methodName blockingFirst() 为 null")
        }
        return result
    }

    private fun newRequest(className: String): Any? =
        Reflect.onClass(className).newInstance().get()

    private fun isValidNumericId(id: String): Boolean =
        id.isNotEmpty() && id.all { it.isDigit() }

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoints
    // ─────────────────────────────────────────────────────────────────────────

    fun search(keyword: String, page: Int, count: Int, tabType: Int): Any? {
        if (keyword.isEmpty()) throw IllegalArgumentException("query 不能为空")
        val req = newRequest(SEARCH_REQ)!!
        val r = Reflect.on(req)
        r.set("bookshelfSearchPlan", 4)
        r.set("bookstoreTab", 2)
        r.set("clickedContent", "page_search_button")
        r.set("query", keyword)
        r.set("searchSource", Reflect.onClass(SEARCH_SOURCE).call("findByValue", 1).get())
        r.set("searchSourceId", "clks###")
        r.set("tabName", "store")
        r.set("userIsLogin", if (isLogin()) 1.toShort() else 0.toShort())
        r.set("offset", ((page - 1) * count).toLong())
        r.set("count", count.toLong())
        r.set("passback", ((page - 1) * count).toString())
        val tab = Reflect.onClass(SEARCH_TAB_TYPE).call("findByValue", tabType).get()
        if (tab != null) r.set("tabType", tab)
        return callFunction(facadeB(), req, "i0")
    }

    fun getDetail(bookId: String, source: Int, withoutVideo: Boolean): Any? {
        if (!isValidNumericId(bookId)) throw IllegalArgumentException("无效的bookId: $bookId")
        val req = newRequest(DETAIL_REQ)!!
        val r = Reflect.on(req)
        r.set("bookId", bookId.toLong())
        if (source > 0) {
            Reflect.onClass(DETAIL_SOURCE).call("findByValue", source).get()
                ?.let { r.set("source", it) }
        }
        if (withoutVideo) r.set("withoutVideo", true)
        return callFunction(facadeB(), req, "h")
    }

    fun getMultiDetail(bookIds: List<String>): Any? {
        if (bookIds.isEmpty()) throw IllegalArgumentException("book_id 不能为空")
        val req = newRequest(MULTI_DETAIL_REQ)!!
        Reflect.on(req).set("bookIds", bookIds.map { it.toLong() })
        return callFunction(facadeB(), req, "u0")
    }

    fun getCatalog(bookId: String): Any? {
        if (!isValidNumericId(bookId)) throw IllegalArgumentException("无效的bookId: $bookId")
        // Request class renamed across releases: ForItemId (≤73332) → ForInfo (hotfix).
        for (reqClass in listOf(DIRECTORY_REQ, DIRECTORY_REQ_V2)) {
            val req = newRequest(reqClass) ?: continue
            Reflect.on(req).set("bookId", bookId.toLong())
            val result = callFunction(facadeB(), req, "K")
            if (result != null) return result
        }
        throw RuntimeException("目录请求失败（两个请求类均无响应）")
    }

    fun getContent(bookId: String, itemId: String, novelTextType: Int): Any? {
        val response = callFunction(facadeF(), createFullRequest(bookId, itemId, novelTextType), "k")
        val data = Reflect.on(response).field("data").get()
            ?: throw RuntimeException("Content data is null")
        val decrypted = decryptContent(data, bookId, itemId)
        Reflect.on(response).set("data", decrypted)
        return response
    }

    fun getBatchContent(bookId: String, itemIds: List<String>): Any? {
        if (itemIds.isEmpty()) throw IllegalArgumentException("item_ids 不能为空")
        if (!isValidNumericId(bookId)) throw IllegalArgumentException("无效的bookId: $bookId")
        val req = newRequest(BATCH_FULL_REQ)!!
        val r = Reflect.on(req)
        r.set("bookId", bookId)
        // itemIds 为逗号分隔字符串（新版模型），keyRegisterTs 与 reqType 必填。
        r.set("itemIds", itemIds.joinToString(","))
        r.set("keyRegisterTs", runCatching {
            val svc = chapterService() ?: return@runCatching 0
            (Reflect.on(svc).call("d", getUserId()).get() as? Number)?.toInt() ?: 0
        }.getOrDefault(0))
        runCatching {
            val reqType = HookSupport.dragonLoader()
                .loadClass("readersaas.com.dragon.read.saas.rpc.model.BatchFullReqType")
                .getDeclaredField("Download").get(null)
            r.set("reqType", reqType)
        }
        val response = callFunction(batchFacade(), req, "h")
        val data = Reflect.on(response).field("data").get()
        if (data is Map<*, *>) {
            val decrypted = HashMap<Any?, Any?>()
            for ((k, v) in data) {
                decrypted[k] = try {
                    decryptContent(v!!, bookId, k.toString())
                } catch (t: Throwable) {
                    v
                }
            }
            Reflect.on(response).set("data", decrypted)
        }
        return response
    }

    fun getBookShelfInfo(): Any? =
        callFunction(facadeB(), newRequest(SHELF_INFO_REQ), "z")

    fun addBookShelfInfo(bookId: String, type: String): Any? {
        if (!isValidNumericId(bookId)) throw IllegalArgumentException("无效的bookId: $bookId")
        val req = newRequest(ADD_SHELF_REQ)!!
        val r = Reflect.on(req)
        r.set("bookId", arrayListOf(bookId))
        r.set("addBookSource", Reflect.onClass(ADD_SHELF_SOURCE).enumValue("User").get())

        val bookType = if (type == "listen") "Listen" else "Read"
        val typeEnum = Reflect.onClass(READING_BOOK_TYPE).enumValue(bookType).get()
        val identify = newRequest(SHELF_IDENTIFY)!!
        Reflect.on(identify).set("asterisked", false)
            .set("bookId", bookId)
            .set("bookType", typeEnum)
            .set("modifyTime", 0L)
        r.set("identifyData", arrayListOf(identify))
        return callFunction(facadeB(), req, "c")
    }

    fun getAudioPlayURL(bookId: String, itemId: String, toneQuality: Int, toneId: Long): Any? {
        if (!isValidNumericId(bookId) || !isValidNumericId(itemId)) {
            throw IllegalArgumentException("无效的bookId或itemId")
        }
        val req = newRequest(AUDIO_URL_REQ)!!
        val r = Reflect.on(req)
        r.set("bookId", bookId.toLong())
        r.set("itemId", itemId.toLong())
        r.set("toneId", if (toneId > 0) toneId else 80L)
        r.set("useServerHistory", false)
        r.set("isToneInherit", true)
        r.set("isLocalBook", false)
        r.set("reqType", Reflect.onClass(AUDIO_URL_REQ_TYPE).enumValue("PLAY").get())
        val quality = Reflect.onClass(TONE_QUALITY)
            .call("findByValue", if (toneQuality > 0) toneQuality else 128).get()
            ?: Reflect.onClass(TONE_QUALITY).enumValue("HighQuality").get()
        r.set("toneQuality", quality)
        return callFunction(facadeB(), req, "f")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Content decryption (from MK DragonService, v73332-verified)
    // ─────────────────────────────────────────────────────────────────────────

    private fun createFullRequest(bookId: String, itemId: String, novelTextType: Int): Any? {
        val req = newRequest(FULL_REQ)!!
        val r = Reflect.on(req)
        r.set("bookId", bookId)
        r.set("itemId", itemId)
        if (novelTextType > 0) {
            Reflect.onClass(NOVEL_TEXT_TYPE).call("findByValue", novelTextType).get()
                ?.let { r.set("novelTextType", it) }
        }
        // keyRegisterTs: registration timestamp of the content key (service.d).
        r.set("keyRegisterTs", runCatching {
            val svc = chapterService() ?: return@runCatching 0
            (Reflect.on(svc).call("d", getUserId()).get() as? Number)?.toInt() ?: 0
        }.getOrDefault(0))
        return req
    }

    private fun decryptContent(itemContent: Any, bookId: String, chapterId: String): Any {
        val cryptStatus = (Reflect.on(itemContent).field("cryptStatus").get() as? Number)?.toInt() ?: 0
        val content = Reflect.on(itemContent).field("content").get() as? String ?: return itemContent
        val keyVersion = (Reflect.on(itemContent).field("keyVersion").get() as? Number)?.toInt() ?: 0
        val compressStatus = (Reflect.on(itemContent).field("compressStatus").get() as? Number)?.toInt() ?: 0

        return when (cryptStatus) {
            0 -> performDecryption(itemContent, content, keyVersion, compressStatus, bookId, chapterId)
            1 -> itemContent // not encrypted
            2 -> performDecryption(itemContent, content, keyVersion, compressStatus, bookId, chapterId) // key expired → retry with fresh
            else -> itemContent
        }
    }

    private fun performDecryption(
        itemContent: Any,
        encryptedContent: String,
        keyVersion: Int,
        compressStatus: Int,
        bookId: String,
        chapterId: String,
    ): Any {
        // 1. Fetch the decryption key (service.a/b → Single → blockingGet).
        val plainTextKey = fetchKey(keyVersion)
            ?: return itemContent.also {
                HookSupport.log?.warn("[$TAG] 密钥获取失败，返回密文 (keyVersion=$keyVersion)")
                Reflect.on(it).set("content", encryptedContent)
            }

        // 2. Plaintext flag: legacy key.c() → new DecryptKey.f field.
        val isPlain = runCatching {
            try {
                Reflect.on(plainTextKey).call("c").getBoolean()
            } catch (t: Throwable) {
                (Reflect.on(plainTextKey).field("f").get() as? Boolean) ?: false
            }
        }.getOrDefault(false)
        if (isPlain) {
            Reflect.on(itemContent).set("content", encryptedContent)
            return itemContent
        }

        // 3. Decrypt via the same chapter service instance (service.r).
        val plaintext = runCatching {
            val svc = chapterService() ?: return@runCatching null
            Reflect.on(svc).call("r", encryptedContent, plainTextKey,
                compressStatus > 0, bookId, chapterId).get() as? String
        }.getOrNull() ?: encryptedContent

        Reflect.on(itemContent).set("content", plaintext)
        return itemContent
    }

    /**
     * 密钥获取：
     *  - keyVersion == MIN_VALUE → DecryptKey 明文持有者（static g → holder.b()）
     *  - 其余 → service.b(keyVersion, userId) 优先，失败退 service.a(userId)
     *    （两者都返回 Single<DecryptKey>，blockingGet 阻塞取值）
     */
    private fun fetchKey(keyVersion: Int): Any? {
        if (keyVersion == Int.MIN_VALUE) return getPlainTextKey()
        val svc = chapterService() ?: return null
        return runCatching {
            val single = runCatching {
                Reflect.on(svc).call("b", keyVersion, getUserId()).get()
            }.getOrElse {
                Reflect.on(svc).call("a", getUserId()).get()
            }
            Reflect.on(single).call("blockingGet").get()
        }.getOrNull()
    }

    /** DecryptKey 明文密钥持有者：字段名 f183364g（v73332）→ g（热更后）。 */
    private fun getPlainTextKey(): Any? {
        val cls = HookSupport.dragonLoader().loadClass(DECRYPT_KEY)
        for (fieldName in listOf("f183364g", "g")) {
            val field = runCatching {
                cls.getDeclaredField(fieldName).apply { isAccessible = true }
            }.getOrNull() ?: continue
            val holder = field.get(null) ?: continue
            return runCatching { Reflect.on(holder).call("b").get() }.getOrNull() ?: holder
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun isLogin(): Boolean = try {
        Reflect.onClass("com.dragon.read.user.AcctManager").call("g")
            .call("islogin").getBoolean()
    } catch (t: Throwable) {
        false
    }

    private fun getUserId(): String = try {
        if (!isLogin()) "0"
        else Reflect.onClass("com.dragon.read.user.AcctManager").call("g")
            .call("getUserId").get()?.toString() ?: "0"
    } catch (t: Throwable) {
        "0"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialisation: app RPC models → JSON via org.json reflection walk
    // ─────────────────────────────────────────────────────────────────────────

    private fun ok(data: Any?): String {
        val result = JSONObject()
        when {
            data == null -> result.put("code", -1).put("msg", "null response")
            data is Throwable -> result.put("code", -1).put("msg", "${data.javaClass.simpleName}: ${data.message}")
            else -> result.put("code", 0).put("data", toJsonValue(data))
        }
        return result.toString()
    }

    /** Shallow reflection walk: public fields + simple getters → JSON. */
    private fun toJsonValue(value: Any?, depth: Int = 0): Any? {
        if (depth > 6) return value.toString()
        return when {
            value == null -> JSONObject.NULL
            value is String || value is Boolean || value is Int ||
                value is Long || value is Double || value is Float -> value
            value is Short || value is Byte -> value.toInt()
            value is Enum<*> -> value.name
            value is List<*> -> org.json.JSONArray().apply {
                value.take(200).forEach { put(toJsonValue(it, depth + 1)) }
            }
            value is Map<*, *> -> JSONObject().apply {
                value.entries.take(200).forEach { (k, v) -> put(k.toString(), toJsonValue(v, depth + 1)) }
            }
            else -> {
                val obj = JSONObject()
                try {
                    for (f in value.javaClass.fields) {
                        runCatching {
                            f.isAccessible = true
                            obj.put(f.name, toJsonValue(f.get(value), depth + 1))
                        }
                    }
                    for (m in value.javaClass.methods) {
                        if (m.parameterCount != 0) continue
                        val name = m.name
                        if (!name.startsWith("get") || name.length <= 3) continue
                        if (name == "getClass") continue
                        val key = name[3].lowercaseChar() + name.substring(4)
                        if (obj.has(key)) continue
                        runCatching {
                            val ret = m.invoke(value) ?: return@runCatching
                            obj.put(key, toJsonValue(ret, depth + 1))
                        }
                    }
                } catch (t: Throwable) {
                    // keep whatever was collected
                }
                if (obj.length() == 0) value.toString() else obj
            }
        }
    }

    /**
     * Diagnostic: resolve the whole decryption chain at runtime.
     *  1. Chapter service (NsReaderServiceApi.IMPL.readerChapterService) + its decrypt method
     *  2. Key class = decrypt method's 2nd parameter
     *  3. DexKit: classes whose method returns the key type (the key provider)
     *  4. Dump service / DecryptKey$a full method lists
     *  5. DexKit: classes with static (String, int) methods (key provider candidates)
     */
    fun debugDecryptChain(): String {
        val obj = JSONObject()

        // 1. Chapter service instance.
        val service = runCatching {
            Reflect.onClass(READER_API).field("IMPL").call("readerChapterService").get()
        }.getOrNull()
        if (service == null) {
            return obj.put("error", "readerChapterService 不可达").toString()
        }
        obj.put("serviceClass", service.javaClass.name)

        // Service's full declared-method list (static + instance, ≤2 params).
        runCatching {
            val svcMethods = org.json.JSONArray()
            for (m in service.javaClass.declaredMethods.sortedBy { it.name }) {
                if (m.parameterCount > 3) continue
                svcMethods.put(
                    "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                )
            }
            obj.put("serviceDeclaredMethods", svcMethods)
        }

        // Decrypt method: 5 params (String, key, boolean, String, String) → String.
        val decryptMethod = service.javaClass.methods.firstOrNull { m ->
            m.parameterCount == 5 &&
                m.parameterTypes[0] == java.lang.String::class.java &&
                m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                m.parameterTypes[3] == java.lang.String::class.java &&
                m.parameterTypes[4] == java.lang.String::class.java &&
                m.returnType == java.lang.String::class.java
        }
        if (decryptMethod != null) {
            obj.put("decryptMethod",
                "${decryptMethod.name}(${decryptMethod.parameterTypes.joinToString { it.name }})")
            obj.put("keyClass", decryptMethod.parameterTypes[1].name)
        }

        // 2. DecryptKey + its $a holder methods.
        runCatching {
            val dk = HookSupport.dragonLoader().loadClass(DECRYPT_KEY)
            val dkDump = org.json.JSONArray()
            for (m in dk.declaredMethods.sortedBy { it.name }) {
                dkDump.put(
                    "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                )
            }
            obj.put("decryptKeyMethods", dkDump)
        }
        runCatching {
            val holder = HookSupport.dragonLoader().loadClass("$DECRYPT_KEY\$a")
            val hDump = org.json.JSONArray()
            for (m in holder.declaredMethods.sortedBy { it.name }) {
                hDump.put(
                    "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                )
            }
            obj.put("holderMethods", hDump)
        }

        // 3. DexKit: classes with static (String, int) → Rx — key provider candidates.
        val candidates = HookSupport.classResolver?.findClassesByMethodSignature(
            paramTypeNames = listOf("java.lang.String", "int"),
            maxResults = 30,
        ) ?: emptyList()
        val providerDetail = JSONObject()
        for (p in candidates) {
            runCatching {
                val cls = HookSupport.dragonLoader().loadClass(p)
                // 只保留同时具备 (String,int)→Rx 和 (String)→数字 的类（密钥提供者特征）。
                val hasKeyGet = cls.declaredMethods.any { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.parameterCount == 2 &&
                        m.parameterTypes[0] == java.lang.String::class.java &&
                        m.parameterTypes[1] == java.lang.Integer.TYPE &&
                        isRxType(m.returnType)
                }
                val hasKeyTs = cls.declaredMethods.any { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.parameterCount == 1 &&
                        m.parameterTypes[0] == java.lang.String::class.java &&
                        (m.returnType == java.lang.Integer.TYPE || m.returnType == java.lang.Short.TYPE)
                }
                if (hasKeyGet) {
                    val methods = org.json.JSONArray()
                    for (m in cls.declaredMethods.sortedBy { it.name }) {
                        if (m.parameterCount > 2) continue
                        methods.put(
                            "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                                "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                        )
                    }
                    providerDetail.put(p + if (hasKeyTs) " [+ts]" else "", methods)
                }
            }
        }
        obj.put("keyProviders", providerDetail)

        // 4. Chapter service reachability.
        obj.put("chapterService", runCatching {
            chapterService()?.javaClass?.name ?: "null"
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" })

        return obj.toString()
    }

    /** Diagnostic: dump class structure (dev endpoint). */
    fun debugClass(name: String): String {
        val obj = JSONObject()
        try {
            val cls = HookSupport.dragonLoader().loadClass(name)
            obj.put("class", cls.name)
            obj.put("isInterface", cls.isInterface)
            obj.put("isKotlinObject", try {
                cls.getDeclaredField("INSTANCE").let { true }
            } catch (t: Throwable) { false })
            obj.put("super", cls.superclass?.name ?: "null")
            obj.put("interfaces", org.json.JSONArray(cls.interfaces.map { it.name }))
            val methods = org.json.JSONArray()
            for (m in cls.declaredMethods.sortedBy { it.name }) {
                methods.put(
                    "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                )
            }
            obj.put("methods", methods)
            val fields = org.json.JSONArray()
            for (f in cls.declaredFields.sortedBy { it.name }) {
                fields.put(
                    "${if (java.lang.reflect.Modifier.isStatic(f.modifiers)) "static " else ""}" +
                        "${f.name}:${f.type.simpleName}"
                )
            }
            obj.put("fields", fields)
        } catch (t: Throwable) {
            obj.put("error", "${t.javaClass.simpleName}: ${t.message}")
        }
        return obj.toString()
    }

    /** Diagnostic: dump method signatures of the two RPC facades (dev endpoint). */
    fun debugFacade(): String {
        val obj = JSONObject()
        for ((label, facade) in listOf("b" to facadeB(), "f" to facadeF())) {
            val entry = JSONObject()
            try {
                val cls = HookSupport.dragonLoader().loadClass(facade)
                entry.put("class", cls.name)
                entry.put("isKotlinObject", try {
                    cls.getDeclaredField("INSTANCE").let { true }
                } catch (t: Throwable) { false })
                val methods = org.json.JSONArray()
                for (m in cls.declaredMethods.sortedBy { it.name }) {
                    methods.put(
                        "${if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""}" +
                            "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                    )
                }
                entry.put("methods", methods)
            } catch (t: Throwable) {
                entry.put("error", "${t.javaClass.simpleName}: ${t.message}")
            }
            obj.put(label, entry)
        }
        return obj.toString()
    }

    private fun int(v: String?, def: Int): Int = v?.toIntOrNull() ?: def
    private fun long(v: String?, def: Long): Long = v?.toLongOrNull() ?: def
    private fun bool(v: String?, def: Boolean): Boolean = v?.toBooleanStrictOrNull() ?: def
}
