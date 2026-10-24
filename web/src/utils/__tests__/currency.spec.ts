import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { currencyColor } from '../currency'

// 后端种子数据里的 12 个币别（db/init/data.sql）
const SEEDED = ['JPY', 'KRW', 'CNY', 'HKD', 'TWD', 'USD', 'EUR', 'GBP', 'SGD', 'THB', 'MYR', 'AUD']

describe('currencyColor', () => {
  it('gives every seeded currency its own color variable', () => {
    const colors = SEEDED.map(currencyColor)
    expect(new Set(colors).size).toBe(SEEDED.length)
    expect(colors).not.toContain('var(--tm-cur-other)')
  })

  it('ignores case and falls back for unknown codes', () => {
    expect(currencyColor('jpy')).toBe(currencyColor('JPY'))
    expect(currencyColor('XYZ')).toBe('var(--tm-cur-other)')
  })

  it('only refers to variables that really exist in tokens.less', () => {
    // vitest 的工作目录是 web/
    const tokens = readFileSync(resolve(process.cwd(), 'src/styles/tokens.less'), 'utf-8')
    const missing = [...SEEDED, 'XYZ']
      .map((code) => /var\((--[\w-]+)\)/.exec(currencyColor(code))![1]!)
      .filter((name) => !tokens.includes(`${name}:`))
    expect(missing).toEqual([])
  })
})
