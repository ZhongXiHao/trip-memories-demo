# Trip APP Api contract v1

## 通用约定

* base path：`/api`。路径一律不带末尾斜杠（`/trips`，不是 `/trips/`），路径里的 id 一律叫 `{tripId}`、`{memberId}` 这样的名字。
* **身份**：开发期用请求头 `X-User-Id`，只在 dev profile 生效；其他环境没有有效登录态一律 401 `UNAUTHENTICATED`。业务接口里的「当前用户」都由后端从登录态取，**不接受客户端传 userId 来表示"我"**。
* **成员 id**：请求体里的「人」一律用 `memberId`（`trip_member.id`），不是 `userId`。`userId` 只在添加成员、成员列表里出现。
* **金额**：整数，单位是「最小单位 × 100」（JPY 1200 → `120000`），字段名带 `Minor`。币别用 ISO 码，必须在币别列表里。
* **时间**：UTC 的 ISO-8601，带 `Z`。每笔支出另带 `occurredTz`（IANA 时区），「第几天」「某一天」都按它换算，由后端算。
* **非成员访问**：不是该 Trip 的在籍成员，访问 Trip 下的任何接口都返回 404 `NOT_FOUND`（不泄露这个 Trip 是否存在）；是成员但没有权限返回 403 `FORBIDDEN`。
* **列表**：返回 `{"items": [...]}`。v1 不分页，外面包一层是为了以后加分页不用改结构。
* **修改**：`PUT` 是整体替换。带 `version` 的资源做乐观锁，对不上返回 409 `VERSION_CONFLICT`。
* **幂等**：创建支出、创建还款必须带 `clientUuid`（调用方生成，一个「意图」生成一次）。同一个 `(tripId, clientUuid)` 再提交：内容一致返回 200 和已有记录（重复提交不是错误；首次创建是 201）；内容不一致返回 409 `IDEMPOTENCY_KEY_REUSED`。先查已有记录，再做校验。
* **错误结构**：

```json
{"code": "VALIDATION_FAILED", "message": "amountMinor must be > 0", "fields": [{"field": "amountMinor", "message": "must be > 0"}]}
```

`fields` 只在校验失败时出现。调用方（包括 Agent）靠 `code` 决定追问、重试还是放弃，不要解析 `message`。

| 状态码 | code | 什么时候 |
|---|---|---|
| 400 | VALIDATION_FAILED | 字段缺失、格式不对 |
| 400 | AMOUNT_NOT_ROUNDABLE | 金额不是该币别 `roundingUnit` 的整数倍（例如 JPY 1250.50） |
| 400 | SPLIT_SUM_MISMATCH | `custom` 模式分摊之和 ≠ 总额 |
| 400 | MEMBER_NOT_IN_TRIP | 付款人或分摊者不是本行程成员 |
| 400 | MEMBER_LEFT | 涉及已退出的成员（还款的对方除外，见还款） |
| 400 | REPAYMENT_EXCEEDS_DEBT | 还款的销帐金额超过该币别当前欠款 |
| 401 | UNAUTHENTICATED | 没有有效登录态 |
| 403 | FORBIDDEN | 没有权限（不是付款人或记账人却要改删、不是 Owner 却要管成员…） |
| 404 | NOT_FOUND | 资源不存在、已删除，或不是该 Trip 的成员 |
| 409 | VERSION_CONFLICT | 乐观锁冲突 |
| 409 | IDEMPOTENCY_KEY_REUSED | 同一个 `clientUuid` 但内容不同 |
| 409 | ALREADY_MEMBER | 要添加的用户已经是在籍成员 |
| 409 | OWNER_MUST_TRANSFER_FIRST | Owner 要退出，须先转让 |
| 409 | CANNOT_REMOVE_OWNER | 要踢走的是 Owner |
| 409 | REPAYMENT_ALREADY_CONFIRMED | 要取消的还款已经确认 |

## 币别

1. 币别列表：GET /currencies

响应 `{"items": [{"code": "JPY", "name": "日元", "roundingUnit": 100}, ...]}`。`roundingUnit` 是存储单位下的取整单位：JPY、KRW 为 100（整元），其余为 1（到分）。前端据此决定显示几位小数。

## 用户与身份

开发期用 `X-User-Id`，没有登录。下面两个接口先占位，接入 Agent 前会换成「发验证码」和「验证码登录 + Redis token」，现在不定参数。

1. 创建用户：POST /auth/register（仅开发期；请求体 `{nickname}`，返回用户）
2. 用户登录：POST /auth/login（占位）
3. 查看当前用户：GET /users/me，响应 `{"id": 1, "nickname": "Ken"}`

查看其他人的信息只通过「Trip 内成员」接口，且只能看同一个 Trip 的成员，没有 `GET /users/{id}`。

## Trip

Trip 对象：

```json
{
  "id": 1, "name": "東京五日遊", "description": null,
  "startDate": "2026-10-01", "endDate": "2026-10-05",
  "timezone": "Asia/Tokyo", "displayCurrency": "HKD",
  "myMemberId": 3, "myRole": "owner", "createdAt": "2026-09-01T08:00:00Z"
}
```

`myMemberId`、`myRole` 是当前用户在这个 Trip 里的成员 id 和角色，省得客户端再去查成员列表。

### Trip 本身操作

1. 创建 trip：POST /trips
   - 请求体：`name`（必填，≤ 128）、`displayCurrency`（必填，只用于显示概数，不写进任何账目）、`timezone`（必填，IANA，如 `Asia/Tokyo`，用来给支出默认时区和算第几天，所以不设默认值）、`description`、`startDate`、`endDate`（均可选，`endDate` 不能早于 `startDate`）。
   - 创建者自动成为 Owner 成员。响应 201，返回 Trip 对象。
2. trip 详情：GET /trips/{tripId}（仅在籍成员）
3. trip 修改：PUT /trips/{tripId}
   - 仅 Owner。整体替换，字段同创建。Trip 没有 `version`，同时修改时后写覆盖。
   - 改 `timezone` 只影响之后新建支出的默认时区；已有支出按各自的 `occurredTz` 算天，不受影响。改 `startDate` 会让各笔的 `dayIndex` 跟着变（算出来的，不存）。
4. trip 删除：POST /trips/{tripId}/delete（用 POST 符合软删除修改逻辑）
   - 仅 Owner。软删除，响应 204 无内容；之后这个 Trip 下的所有接口（包括再删一次）都返回 404，也不再出现在「我的行程」里。成员和账目的行都保留，只是 Trip 打了删除时间。
5. trip 列表：GET /trips
   - 「我的行程」：当前用户是在籍成员的 Trip，按创建时间倒序。没有 `userId` 参数，永远是当前登录的人。响应 `{"items": [Trip 对象]}`。

### Trip 成员操作

成员对象：

```json
{"memberId": 3, "userId": 1, "nickname": "Ken", "role": "owner", "status": "active", "joinedAt": "2026-09-01T08:00:00Z"}
```

路径里的 `{memberId}` 可以写成 `me`，表示当前用户在这个 Trip 里的成员。

1. 添加成员：POST /trips/{tripId}/members
   - 仅 Owner。请求体 `{"userId": 2}`。响应 201，返回成员对象。
   - `userId` 不存在 → 400 `VALIDATION_FAILED`（`fields` 指向 `userId`）；不用 404，因为 404 在这份契约里专指 Trip 不存在或你不是成员。
   - 该用户已是在籍成员（包括 Owner 添加自己，以及并发地重复添加）→ 409 `ALREADY_MEMBER`；曾经退出过 → 重新加入，沿用原来的 `memberId`（历史账目还指着它），响应 200。
   - v1 只能添加真实用户，不支持规格里的「占位成员」。
2. 成员退出：POST /trips/{tripId}/members/me/quit
   - 把自己设为已退出（`status = left`），不删行。
   - Owner 不能直接退出，须先转让 → 409 `OWNER_MUST_TRANSFER_FIRST`。
   - 响应 200 `{"member": 成员对象, "warnings": [...]}`，`member.status` 是 `left`；`warnings` 的含义见下面「退出和踢走的共同规则」。退出后他已不是在籍成员，所以不能再重复调用（再调是 404）。
3. 踢走成员：POST /trips/{tripId}/members/{memberId}/remove
   - 仅 Owner。效果和退出一样，设为已退出。不能踢 Owner（含自己）→ 409 `CANNOT_REMOVE_OWNER`。对已退出的成员重复调用返回 200，不报错（响应同退出，`warnings` 照常计算）。`{memberId}` 不属于这个 Trip → 404。
4. 转让 Owner：POST /trips/{tripId}/owner/transfer/{memberId}
   - 仅当前 Owner，目标必须是在籍成员且不是自己。同一个事务里先把旧 Owner 降为 member，再升新 Owner（每个 Trip 只能有一个 Owner 的唯一约束要求这个顺序）。响应 200 `{"previousOwner": 成员对象, "newOwner": 成员对象}`。
   - 目标是自己 → 400 `VALIDATION_FAILED`（`fields` 指向 `memberId`）；目标已退出（含并发时刚好退出）→ 400 `MEMBER_LEFT`；`{memberId}` 不属于这个 Trip → 404；调用者不是 Owner → 403。
   - 两个转让并发时只有一个成功，另一个因为发起人已不是 Owner 而得到 403，任何时候 Trip 都恰好有一个 Owner。
5. 列出成员：GET /trips/{tripId}/members
   - 响应 `{"items": [成员对象]}`，包含已退出的成员，靠 `status` 区分；可用 `?status=active` 只看在籍的。Agent 用它把「Ken」「大家」翻译成 `memberId`。
6. 查看 Trip 内其他成员信息：GET /trips/{tripId}/members/{memberId}，响应成员对象

**退出和踢走的共同规则**

- 退出或被踢走时若仍有未结清的欠款，**只提醒，不拦截**：操作照常成功，响应 `{"member": 成员对象, "warnings": [{"code": "UNSETTLED_DEBTS", "message": "...", "debts": [欠款总览里的 items]}]}`，没有欠款时 `warnings` 为空数组。前端想在操作前提醒，可以先调「欠款总览」带上这个成员的 `memberId`。
- 已退出的成员不能再访问这个 Trip（任何接口都是 404）。他的账目保留，名字显示「已退出」。他的个人支出本来就只有他自己看得见，别人依然看不到。
- 欠款不会因为退出而消失，仍在欠款总览里。待确认的还款，在籍的一方依然可以确认或取消（付款方退出，收款方还能确认；收款方退出，付款方只能取消）。
- 重新加入后恢复访问。

## 支出

金额、时间、错误结构见「通用约定」。

1. 记账预览：POST /trips/{tripId}/expenses/preview
2. 创建支出：POST /trips/{tripId}/expenses
3. 支出列表：GET /trips/{tripId}/expenses
4. 支出详情：GET /trips/{tripId}/expenses/{expenseId}
5. 修改支出：PUT /trips/{tripId}/expenses/{expenseId}
6. 删除支出：POST /trips/{tripId}/expenses/{expenseId}/delete

### 请求体（1、2、5 共用）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| clientUuid | string(36) | 创建必填；预览、修改不用 | 幂等键，由调用方生成 |
| title | string(≤128) | 是 | |
| amountMinor | long | 是 | > 0，必须是币别 `roundingUnit` 的整数倍 |
| currency | string(3) | 是 | |
| paidByMemberId | long | 是 | 付款人。个人支出时必须是自己 |
| splitMode | enum | 是 | `equal` / `custom` / `none`（个人支出） |
| splits | array | 看 splitMode | 每项 `{memberId, amountMinor?}`，见下 |
| occurredAt | string | 否 | 默认现在 |
| occurredTz | string | 否 | IANA 时区，默认取 trip 的时区 |
| category | enum | 否 | `flight` `lodging` `food` `transport` `shopping` `ticket` `other`，默认 `other` |
| note | string(≤512) | 否 | |
| source | enum | 否 | `form` `command` `agent`，默认 `form` |
| version | int | 仅修改必填 | 乐观锁，取自详情 |

`splits` 随 `splitMode` 变化：

| splitMode | `splits` 怎么传 | 后端做什么 |
|---|---|---|
| `equal` | 只传成员：`[{"memberId": 3}, {"memberId": 5}]` | 平均分，余数按币别 `roundingUnit` 分配，金额由后端算并返回 |
| `custom` | 成员加金额：`[{"memberId": 3, "amountMinor": 70000}, ...]` | 校验之和等于总额，否则 `SPLIT_SUM_MISMATCH` |
| `none` | 不传或传空数组 | 自动写一条「分给付款人自己，金额 = 总额」；个人支出，可见性固定为 private，且付款人必须是自己 |

不让客户端传的：`visibility`（由 `splitMode` 推出）、`createdByMemberId`（取当前用户）、`equal` 模式下的分摊金额。

### 1. 记账预览

只算不写。校验规则与创建完全相同，但不查、也不占用 `clientUuid`。响应 200：

```json
{
  "amountMinor": 100000,
  "currency": "JPY",
  "splits": [
    {"memberId": 3, "amountMinor": 33400},
    {"memberId": 5, "amountMinor": 33300},
    {"memberId": 7, "amountMinor": 33300}
  ],
  "remainder": {"amountMinor": 100, "memberId": 3},
  "warnings": []
}
```

余数规则（规格 4.6.4）：先给付款人；付款人不在分摊名单里则给名单第一位。上例是 1000 円分 3 人，每人 333 円，余 1 円给付款人（成员 3）。

### 2. 创建支出

先按 `(tripId, clientUuid)` 查已有记录，再校验：已有且内容一致 → 200 返回已有那一笔；已有但内容不一致 → 409 `IDEMPOTENCY_KEY_REUSED`；没有 → 校验通过后创建，201。响应体是「支出对象」。

### 3. 支出列表

查询参数（都可选）：`date`（当地日期，如 `2026-10-03`，按每笔自己的 `occurredTz` 判断）、`memberId`（付款人或分摊者）、`currency`、`category`。
只返回当前用户看得见的：共同支出，加上自己的个人支出。响应 `{"items": [支出对象]}`。

### 支出对象

```json
{
  "id": 12, "tripId": 1, "title": "拉麵", "amountMinor": 120000, "currency": "JPY",
  "paidByMemberId": 3, "createdByMemberId": 3,
  "splitMode": "equal", "visibility": "trip",
  "splits": [{"memberId": 3, "amountMinor": 60000}, {"memberId": 5, "amountMinor": 60000}],
  "occurredAt": "2026-10-03T11:00:00Z", "occurredTz": "Asia/Tokyo", "dayIndex": 3,
  "category": "food", "note": null, "source": "agent", "version": 0,
  "createdAt": "2026-10-03T11:02:10Z"
}
```

`dayIndex` 由后端按 `occurredTz` 换算后，算出距 trip `startDate` 的天数 + 1（trip 没有开始日期时为 null），前端和 Agent 都不要自己算。

### 5. 修改支出

只有付款人或记账人能改（规格 4.6.7）。请求体同上（整体替换）加 `version`；改分摊也会让 `version` 加一。响应 200，返回新的支出对象。
修改金额时，若这笔支出已有已确认的还款关联，不会自动调整还款，差额回到欠款里。

### 6. 删除支出

只有付款人或记账人能删。软删除，之后列表、统计、欠款里都不再出现。

## 欠/还款

**怎么算**：只有共同支出产生欠款（个人支出不分账）。欠款按币别分开记，不做换算，也不跨币别互抵。一对一净额 = 支出产生的欠款 − 已确认还款的销帐金额。待确认（`pending`）的还款不抵债，另列。

### 欠款

欠款对象（`from` 欠 `to`，只返回净额 > 0 的）：

```json
{"fromMemberId": 5, "toMemberId": 3, "currency": "JPY", "amountMinor": 340000, "pendingMinor": 100000}
```

`amountMinor` 是还欠多少；`pendingMinor` 是同方向、已标记但对方还没确认的还款金额。

1. 总览：GET /trips/{tripId}/debts
   - 响应 `{"items": [欠款对象]}`，Trip 内每两人之间、按币别分列。可选参数 `memberId`：只看和这个人有关的。「我欠谁、谁欠我」传自己的 `memberId`。
2. 一对一净额：GET /trips/{tripId}/debts/{memberId}
   - 当前用户和这个成员之间的欠款，按币别分列，响应同上。

### 还款

还款对象：

```json
{
  "id": 8, "settlementGroupId": "7d9c3f0e-...", "expenseId": null,
  "fromMemberId": 5, "toMemberId": 3,
  "clearedAmountMinor": 820000, "clearedCurrency": "JPY",
  "paidAmountMinor": 42640, "paidCurrency": "HKD",
  "status": "pending", "confirmedAt": null, "note": null,
  "createdByMemberId": 5, "version": 0, "createdAt": "2026-10-10T09:00:00Z"
}
```

- 同时保存「销帐」（抵销了多少欠款）和「实付」（实际转了多少钱）两组金额，汇率是衍生值（`paid / cleared`），接口不返回，需要显示时前端自己算。同币别时两组金额相等。
- 一次请求 = 一次转账 = 一个 `settlementGroupId`（取请求的 `clientUuid`）；转账里每条被结清的欠款是一条还款记录。

1. 创建还款：POST /trips/{tripId}/repayments
2. 还款列表：GET /trips/{tripId}/repayments
3. 收款人确认（单条）：POST /trips/{tripId}/repayments/{repaymentId}/confirm
4. 收款人确认（整组）：POST /trips/{tripId}/repayments/groups/{settlementGroupId}/confirm
5. 取消待确认（单条）：POST /trips/{tripId}/repayments/{repaymentId}/cancel
6. 取消待确认（整组）：POST /trips/{tripId}/repayments/groups/{settlementGroupId}/cancel

### 1. 创建还款

请求体：

| 字段 | 必填 | 说明 |
|---|---|---|
| clientUuid | 是 | 幂等键，同时作为 `settlementGroupId` |
| fromMemberId | 是 | 付款方 |
| toMemberId | 是 | 收款方，不能和付款方相同 |
| paidCurrency | 是 | 实际转账的币别 |
| items | 是 | 要结清的欠款，每项 `{clearedCurrency, clearedAmountMinor, paidAmountMinor}`；各项 `clearedCurrency` 不能重复；同币别时 `paidAmountMinor` 必须等于 `clearedAmountMinor` |
| expenseId | 否 | 针对某一笔支出还款时填；该笔必须是 `toMemberId` 付的款，且 `fromMemberId` 在它的分摊里；此时 `items` 只能有一项 |
| note | 否 | ≤ 512 |

规则：

- 创建者必须是 `fromMemberId` 或 `toMemberId` 之一，其余人 403。
- **创建者是付款方**（标记已还款）→ 状态 `pending`，等收款方确认；**创建者是收款方**（直接记「已收到」）→ 状态直接 `confirmed`。
- 创建者必须是在籍成员；对方可以已退出（收款方要记下「已收到」一个已退出的人的还款，应该可以）。
- 每项 `clearedAmountMinor` 不能超过该币别当前欠款，也就是 `amountMinor` 减去同方向其他待确认的还款，否则 400 `REPAYMENT_EXCEEDS_DEBT`。少还（部分结清）允许，剩下的欠款继续存在。金额也要是币别 `roundingUnit` 的整数倍。
- 响应 201 `{"settlementGroupId": "...", "items": [还款对象]}`；按幂等规则重复提交返回 200。

### 2. 还款列表

查询参数（都可选）：`status`（`pending` / `confirmed`）、`memberId`（付款方或收款方是他）、`settlementGroupId`。
响应 `{"items": [还款对象]}`。只返回当前用户是付款方或收款方的还款。

### 3、4. 确认

- 只有收款方（`toMemberId`）能确认，其他人 403。
- 整组确认是一条 `UPDATE`（只更新该组里 `pending` 且没取消的），不会因为组里有已取消的一条整体失败。
- 重复确认已经确认的返回 200，不报错。已取消的返回 404。
- 响应：单条返回还款对象；整组返回 `{"items": [该组当前所有还款对象]}`。

### 5、6. 取消

- 付款方、收款方任一方都可以取消，只适用于 `pending` 的还款。已确认的 → 409 `REPAYMENT_ALREADY_CONFIRMED`。软删除，之后列表里不再出现。
- 对已经取消的重复取消返回 200。响应格式同确认。
- 确认和取消同时发生时，以先成功的为准：两者都是带「`pending` 且未取消」条件的单条 `UPDATE`，看影响行数，不会出现「确认了一条已取消的」。

### 修改还款

不提供修改接口。待确认的还款要改，取消后重新创建；**已确认的还款不能删除，也不能改**，因为它是历史事实。这一条和规格 4.6.5 的「还款可以修改或删除」不同，是有意的：记错了的话，目前没有冲销记录，是留下的问题。

## 规则指令

规则指令只解析、不写库，是 Agent 评测（E1）的基线，由纯规则实现，不调用模型。Agent 不调用本接口。
用户在预览卡片上确认后，前端用返回的 `draft` 调「创建支出」（补上 `clientUuid`），落库的支出 `source = command`。指令列建立的内容与表单建立的完全一样。

1. 解析指令：POST /trips/{tripId}/commands/parse

请求体：`{"input": "/pay 1200jpy 拉麵 @Ken @Amy"}`，`input` 必填，≤ 500 字符，空串返回 400 `VALIDATION_FAILED`。

响应（解析失败也是 200：失败是正常结果，评测要统计；只有请求本身不合法才是 4xx）：

```json
{
  "status": "ok",
  "command": "pay",
  "kind": "expense",
  "draft": {
    "title": "拉麵", "amountMinor": 120000, "currency": "JPY",
    "paidByMemberId": 3, "splitMode": "equal",
    "splits": [{"memberId": 3}, {"memberId": 5}, {"memberId": 7}],
    "source": "command"
  },
  "preview": { "同「记账预览」的响应" },
  "view": null,
  "issues": [],
  "warnings": []
}
```

| 字段 | 说明 |
|---|---|
| status | `ok` 可以直接预览确认；`failed` 有无法识别的部分；`unsupported` 指令认得但还没开放 |
| command | 别名归一后的名字（`pay` `owe` `today`…）；认不出为 null |
| kind | `expense`（产出支出草稿）或 `view`（查看类，不产出草稿） |
| draft | 请求体同「创建支出」，不含 `clientUuid`。`failed` 时是已识别出的部分（可能缺字段），给前端「改用表单」带入；`kind=view` 时为 null |
| preview | 同「记账预览」的响应；仅 `status=ok` 时有 |
| view | `kind=view` 时给出要看什么：`debts`（调欠款总览）或 `today_expenses`（调支出列表，`date` 为今天） |
| issues | 无法识别的部分，每项 `{code, text, message}`，`text` 是输入里对应的原文片段 |
| warnings | 已解析成功、但用户该留意的事，结构同 issues |

issues 的 code：`NOT_A_COMMAND`（不以 `/` 开头）、`UNKNOWN_COMMAND`、`COMMAND_NOT_AVAILABLE`、`MISSING_AMOUNT`、`AMBIGUOUS_AMOUNT`（出现多个金额）、`UNKNOWN_CURRENCY`、`AMOUNT_NOT_ROUNDABLE`、`MISSING_TITLE`、`UNKNOWN_MEMBER`、`AMBIGUOUS_MEMBER`（昵称重名）、`MEMBER_LEFT`。
warnings 的 code：`CURRENCY_DEFAULTED`（没写币别，取 trip 展示币别）、`SPLIT_ALL_MEMBERS`（没写 `@`，全体均分）、`PAYER_ADDED_TO_SPLIT`（付款人不在 `@` 里，已自动加入）。

### `/pay` 的解析规则（规格 3.8.3）

别名：`/记账` `/記帳`。参数顺序不限，按下面的规则从输入里依次取走，剩下的文字就是 `title`。

- 金额币别：数字紧挨一个 3 位币别码，大小写不限（`1200jpy`、`jpy1200`），数字可带千分位逗号和最多 2 位小数。金额按币别 ×100 换成 `amountMinor`，并检查是 `roundingUnit` 的整数倍。
  v1 不认 `¥` `$` 这类符号（`¥` 既可能是日元也可能是人民币），遇到按 `UNKNOWN_CURRENCY` 处理。
- 付款人：默认是自己；`由 @成员` 或 `by @成员` 指定别人。
- 分摊者：其余的 `@成员` 加上付款人，平均分摊（`splitMode = equal`）；没写任何 `@` 则全体在籍成员均分。
- 成员匹配：`@` 后面的名字和本行程在籍成员的昵称做精确匹配，忽略大小写。匹配不到或重名都不猜，进 `issues`。
  这是基线刻意保持的笨：模糊匹配、别名、「阿 Ken」这类叫法留给 Agent 去做，E1 评测靠这个差距体现 Agent 的价值。
- 不支持：逐人指定金额、个人支出（`none`）、日期时间、类别。要这些请用表单。`occurredAt` 取现在，`occurredTz` 取 trip 时区，`category = other`。

### 查看类指令

| 指令 | 别名 | 结果 |
|---|---|---|
| `/owe` | `/欠款` | `kind=view`，`view=debts` |
| `/today` | `/今天` | `kind=view`，`view=today_expenses` |

### 暂未开放

`/todo` `/visited` `/missed` `/skip` `/mood` `/note` 等，对应的表还没建。认得但返回 `status=unsupported`、`COMMAND_NOT_AVAILABLE`，不要当成 `UNKNOWN_COMMAND`，以免评测把它们算成解析错误。

## 人/天数统计

按天、按人看花了多少。数字全部由后端算，Agent 只转述。

通用规则：

- 只统计没删除的支出，按币别分列，**不换算**（展示汇率还没有表，规格 4.6.1 也规定没设展示汇率时各币别独立显示）。所以 v1 没有「人均花费」这种跨币别合计。
- 「天」按每笔支出自己的 `occurredTz` 换算成当地日期后分组，在 Java 里做，不用 SQL 的时区函数。`dayIndex` 是该日期距 trip `startDate` 的天数 + 1，trip 没设开始日期时为 null。
- 可见性：不带 `memberId` 时只统计共同支出（`visibility = trip`）。个人支出只出现在「自己」的统计里，别人的统计里永远没有，不会泄露。
- 没有支出的日子或成员不返回（不补零）。

1. 按天统计：GET /trips/{tripId}/stats/days
2. 按人统计：GET /trips/{tripId}/stats/members

### 1. 按天统计

查询参数（都可选）：

| 参数 | 说明 |
|---|---|
| memberId | 只看这个成员的分摊额。是自己时含个人支出；是别人时只含共同支出 |
| currency | 只看某个币别 |

响应：

```json
{
  "items": [
    {"dayIndex": 3, "date": "2026-10-03", "currency": "JPY", "totalMinor": 560000, "count": 3},
    {"dayIndex": 3, "date": "2026-10-03", "currency": "HKD", "totalMinor": 30000, "count": 1}
  ]
}
```

`totalMinor`：不带 `memberId` 时是当天共同支出的金额之和；带 `memberId` 时是该成员当天的分摊额之和。`count` 是支出笔数。按日期、币别排序。

### 2. 按人统计

查询参数（都可选）：

| 参数 | 说明 |
|---|---|
| date | 当地日期，如 `2026-10-03`，只看这一天（人 × 天） |
| currency | 只看某个币别 |

响应：

```json
{
  "items": [
    {"memberId": 3, "currency": "JPY", "paidMinor": 480000, "shareMinor": 260000, "paidCount": 2},
    {"memberId": 5, "currency": "JPY", "paidMinor": 0, "shareMinor": 260000, "paidCount": 0}
  ]
}
```

- `paidMinor`：该成员作为付款人垫付的金额之和。
- `shareMinor`：该成员被分摊到的金额之和。
- `paidCount`：垫付的笔数。
- 已退出的成员只要有支出也会出现。
- 对别人来说，这些数字不含对方的个人支出，所以只能看成「共同支出部分」，不是对方的总花费。
- 垫付多少、应分摊多少，和「谁欠谁」是两回事，欠款一律看欠款接口。
