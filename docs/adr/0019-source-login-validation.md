# ADR-0019: 漫画源在线登录状态校验

- 状态：已接受
- 日期：2026-11-23

上游协议参考：[Comic Source Account](https://github.com/venera-app/venera/blob/master/doc/comic_source.md#account) 和 [Javascript Network API](https://github.com/venera-app/venera/blob/master/doc/js_api.md#network)。

## 背景

`ComicSource.isLogged` 仅根据来源私有数据中是否存在 `token` 或 `account` 判断。它能说明本地保存过会话信息，但无法判断服务器是否仍接受该会话。原管理页把该值直接呈现为“已登录”，并隐藏账号密码表单，导致会话失效后必须先执行退出才能尝试重新登录。

## 决策

- 将本地会话存在与在线有效性区分。界面将前者说明为“本地已保存登录信息”，不称其为已验证有效。
- 漫画源可以提供 `account.validate(): Promise<boolean>`，也可使用上游 `account.loginWithCookies.fields` / `account.loginWithCookies.validate(values)` 协议。后者从该来源 CookieJar 读取指定 URL 下的 Cookie，并按 `fields` 顺序传值。Host 以 `true` / `false` 显示有效或失效；脚本异常、非布尔值和网络错误显示为无法确认。没有受支持校验函数时显示不支持在线检测。
- 来源管理设置提供主动检测入口。
- 已保存会话仍可展开账号密码表单直接再次调用 `account.login`，无需先调用 `logout`。账号密码只用于当次请求，不持久化。

## 后果

- 只有实现受支持校验协议的来源可以提供在线有效性结论；其他既有来源不会被错误标记为有效，也无需改变即可继续使用。Cookie 只按当前来源和 HTTPS URL 匹配，不提供其他来源的 Cookie。
- 重新登录是否能覆盖旧会话仍由来源脚本的 `account.login` 实现决定；Native Host 不擅自清除来源私有数据，以免丢失来源自定义会话状态。
