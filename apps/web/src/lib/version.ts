// 由 Vite 从仓库根 package.json 注入；前端、后端构建与发布脚本共享同一版本源。
declare const __MELORA_VERSION__: string

export const APP_VERSION = __MELORA_VERSION__
