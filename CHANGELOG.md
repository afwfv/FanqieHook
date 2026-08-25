# 更新日志

> 发布时，按 module.prop 中的版本号自动读取对应「## x.x.x」段作为模块中心的更新说明；
> 找不到对应版本段时，回退使用「未发布」段。
> 每次在 debug 分支改动代码后，把中文说明补到当前版本段（发版前记得递增 versionCode / version）。

## 未发布

## 0.3.0
v0.3.0 - 红果短剧 + 开屏广告拦截
新增红果短剧广告拦截（banner、贴片、横屏插入）与热启动开屏广告 Activity 级阻断
作用域覆盖 com.dragon.read 与 com.phoenix.read
番茄侧与红果侧 hook 全部安装成功；红果开屏、短剧 banner/贴片广告消失
安装：LSPosed -> 模块 -> 勾选 FanqieHook -> 作用域选 com.dragon.read 与 com.phoenix.read
