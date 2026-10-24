import { http } from './http'

/** 对应后端 CurrencyView。roundingUnit 是"最小单位 × 100"下的取整单位：JPY/KRW 为 100（整元），其余 1（到分）。 */
export interface Currency {
  code: string
  name: string
  roundingUnit: number
}

/** GET /currencies：支持的币别（后端按代码字母序） */
export async function listCurrencies(): Promise<Currency[]> {
  const { data } = await http.get<{ items: Currency[] }>('/currencies')
  return data.items
}
