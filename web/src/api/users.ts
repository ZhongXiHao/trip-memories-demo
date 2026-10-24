import { http } from './http'

/** 对应后端 UserView */
export interface UserView {
  id: number
  nickname: string
}

/** GET /users/me：当前身份对应的用户。也用来验证一个 id 是否真的存在。 */
export async function getMe(): Promise<UserView> {
  const { data } = await http.get<UserView>('/users/me')
  return data
}

/** POST /auth/register：创建用户。仅后端 dev profile 下存在，正式环境是 404。 */
export async function registerDevUser(nickname: string): Promise<UserView> {
  const { data } = await http.post<UserView>('/auth/register', { nickname })
  return data
}
