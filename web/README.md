# web

Trip Memories 的前端：Vue 3 + TypeScript + Vite，手机优先（Vant 4）。

## 启动

```bash
npm ci            # 第一次，按 package-lock.json 装依赖
npm run dev       # http://localhost:5173
```

前端依赖后端：先 `docker compose up -d`（仓库根目录），再用 `dev` profile 启动 `api/`（默认 8080）。

## 常用命令

| 命令 | 作用 |
|---|---|
| `npm run dev` | 开发服务器（热更新） |
| `npm run build` | 类型检查（`vue-tsc`）+ 打包 |
| `npm run test:unit` | 单元测试（Vitest，加 `-- --run` 只跑一次） |
| `npm run lint` | oxlint + ESLint（会自动修复） |

## 和后端怎么连

`vite.config.ts` 里把 `/api` 代理到 `http://localhost:8080`。后端的 context-path 本来就是 `/api`，
所以前端直接请求 `/api/trips` 即可，**不需要改写路径，也不需要后端开 CORS**（浏览器看到的是同源）。

开发期身份靠请求头 `X-User-Id`（后端只在 dev profile 认它）。请求都应该走统一的 axios 实例，
以后换成验证码登录 + token 时只改那一处。

## 目录约定

```
src/
  api/        请求层。http.ts 是唯一的 axios 实例；errors.ts 把所有失败统一成 ApiError；
              session.ts 管"当前身份存在哪"；其余按资源分文件（users.ts、以后 trips.ts、members.ts…）
  stores/     Pinia（当前身份、以后的当前 Trip 等跨页面状态）
  views/      路由页面
  components/ 可复用组件（BoardHeader 等）
  utils/      纯函数和小工具（金额、时间格式化、toast 等），适合写单元测试
  router/     路由表和"没身份就去选择身份页"的守卫
  styles/     tokens.less（设计令牌）、base.less（全局基础样式）
```

写页面前先看 [`DESIGN.md`](./DESIGN.md)：配色、字号、间距、规则都在那里。
界面分两套版式：窄于 960px 是手机版式，宽于它是 PC 版式（顶部导航条 + 右上角头像菜单），
页面容器用法见 DESIGN.md 的"两套版式"。

## 请求怎么写

接口函数只管发请求和声明类型，不处理错误提示：

```ts
// src/api/users.ts
export async function getMe(): Promise<UserView> {
  const { data } = await http.get<UserView>('/users/me')
  return data
}
```

调用方（页面或 store）用 `try/catch` 接 `ApiError`：用 `error.code` 判断情况，
`error.fieldMessage('nickname')` 取字段错误，`showError(error)`（`utils/toast.ts`）弹统一的中文提示。
收到 401 时 `http.ts` 会自动清掉身份并回到选择身份页，页面不用单独处理。

## 开发期身份

后端没有登录，靠请求头 `X-User-Id`（只在后端 dev profile 下认）。`/select-user` 页面可以创建开发用户
（`POST /auth/register`，仅 dev 环境）、切换身份，已用过的账号记在浏览器本地。
接入验证码登录后，这个页面会被登录页替换，`api/session.ts` 和 `http.ts` 是需要改的两处。

## 组件库和样式

- 组件：Vant 4，**模板里直接写 `<van-button>` 即可**，组件和样式由 `unplugin-vue-components` 按需自动引入，
  类型声明生成在 `src/components.d.ts`（会自动更新，需要提交）。
- 样式：`<style lang="less" scoped>`，Less 已装好。
- 桌面浏览器调试手机交互：Chrome 开发者工具里切到设备模拟模式。

## 对接契约时要注意

- 金额是整数的"最小单位 × 100"（JPY 1200 → `120000`），字段名带 `Minor`；显示时按 `GET /currencies` 的 `roundingUnit`。
- 时间是 UTC 的 ISO-8601 带 `Z`；每笔支出另有 `occurredTz`，按它换算。
- 错误体是 `{ code, message, fields? }`，表单可用 `fields[].field` 把错误挂到对应输入框。
- 人一律用 `memberId`（`trip_member.id`），不是 `userId`。

完整契约见仓库根目录 `docs/api-contract.md`。
