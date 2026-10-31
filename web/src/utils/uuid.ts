/**
 * 创建支出的幂等键（clientUuid）：一次"记一笔"生成一次，重试时沿用，后端据此识别重复提交。
 * crypto.randomUUID 只在安全上下文（https、localhost）里有；用手机通过局域网 IP 打开开发服务器时没有，
 * 这时用 getRandomValues 拼一个同样格式的 v4 UUID。
 */
export function newClientUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40 // 版本 4
  bytes[8] = (bytes[8]! & 0x3f) | 0x80 // RFC 4122 变体
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
