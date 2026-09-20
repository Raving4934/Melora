# 演示音源来源

核验日期：2026-09-07。本 Provider 不连接商业音乐平台，也不读取或执行仓库中的音源脚本。只有以下两首实际录音，多个主题歌单重复收录，榜单为演示编排而非热度数据。

## Maple Leaf Rag

- 作曲：Scott Joplin，1899 年。
- 演奏／录音：United States Marine Band，1906 年；不是 Joplin 本人演奏。
- Wikimedia Commons 文件页：`https://commons.wikimedia.org/wiki/File:1906_-_Scott_Joplin%27s_Maple_Leaf_Rag_(1899)_played_by_the_United_States_Marine_Band.ogg`
- 该文件的 Commons `imageinfo/extmetadata` 返回 `LicenseShortName=Public domain`、`Copyrighted=False`、`License=pd`、`AttributionRequired=false`。分类包括 PD US Marines、PD-old-70-expired、PD US record expired。
- Commons 引用的原始档案：`https://archive.org/details/UnitedStatesMarineBand`。
- 直接媒体地址在 `demo.go`；核验 HEAD 得到 HTTP 200、`application/ogg`、1,840,346 字节、114.835 秒、`Access-Control-Allow-Origin: *`。

## The Entertainer

- 作曲：Scott Joplin，1902 年。
- 演奏／录音：Wikimedia 用户 IE，2007 年 9 月，Casio WK-3300 电子键盘现场录音；不是 Scott Joplin 本人录音，也不是原声钢琴录音。
- Wikimedia Commons 文件页：`https://commons.wikimedia.org/wiki/File:The_Entertainer_-_Scott_Joplin.ogg`
- 经英文 Wikipedia 共享文件的 `imageinfo/extmetadata` 核验：`LicenseShortName=Public domain`、`Copyrighted=False`、`License=pd`、`AttributionRequired=false`。
- 直接媒体地址在 `demo.go`；核验 HEAD 得到 HTTP 200、`application/ogg`、4,495,868 字节、233.851 秒、`Access-Control-Allow-Origin: *`。

## 复核方法与边界

文件页抓取返回 403 时，使用 Wikimedia 官方 MediaWiki API 获取文件元数据，不以搜索摘要替代授权信息。此次可用的 API 请求：

```text
https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cextmetadata&titles=File%3A1906_-_Scott_Joplin%27s_Maple_Leaf_Rag_%281899%29_played_by_the_United_States_Marine_Band.ogg
https://en.wikipedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cextmetadata&titles=File%3AThe_Entertainer_-_Scott_Joplin.ogg
```

`standard` 仅代表原始 Ogg Vorbis 文件，不承诺 320 kbps 或无损。不提供歌词、音频代理、转码、ID3/Vorbis 标签写入、封面或歌词 sidecar。Provider health-check 只检查内置目录就绪，不声称已探测远端 CDN。远端可用性会变化，播放由浏览器直连；只有下载 Worker 读取媒体内容。封面为产品自己的 `/covers/*.svg`，不是唱片原始封面。
