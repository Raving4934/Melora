// 仅保存用户主动选择记住的登录名，不存任何凭据或鉴权状态。
const USERNAME_KEY = 'melora:login-username:v1'

export function readRememberedUsername(): string {
  try {
    return window.localStorage.getItem(USERNAME_KEY) || ''
  } catch {
    return ''
  }
}

export function writeRememberedUsername(value: string) {
  try {
    if (value) window.localStorage.setItem(USERNAME_KEY, value)
    else window.localStorage.removeItem(USERNAME_KEY)
  } catch {
    // 存储不可用时不阻塞登录。
  }
}
