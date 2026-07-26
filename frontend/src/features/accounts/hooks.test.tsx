import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { list, holdings, prices, priceHistory, priceIntraday, securityInsight } = vi.hoisted(() => ({
  list: vi.fn(),
  holdings: vi.fn(),
  prices: vi.fn(),
  priceHistory: vi.fn(),
  priceIntraday: vi.fn(),
  securityInsight: vi.fn(),
}))

vi.mock('./api', () => ({
  accountsApi: { list, holdings, prices, priceHistory, priceIntraday, securityInsight },
}))

const {
  useHoldingsWithLivePrices,
  usePortfolio,
  usePriceHistory,
  useSecurityInsight,
} = await import('./hooks')

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useHoldingsWithLivePrices', () => {
  beforeEach(() => {
    list.mockReset()
    holdings.mockReset()
    prices.mockReset()
  })

  it('uses the backend EUR cost basis when enriching a foreign quote', async () => {
    holdings.mockResolvedValue([
      {
        ticker: 'US',
        name: 'US share',
        quantity: 2,
        averageBuyIn: 80,
        currentPrice: 100,
        quoteCurrency: 'USD',
        currentValueEur: 180,
        costBasisEur: 140,
        pnlEur: 40,
        pnlPercent: 28.57,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
      },
    ])
    prices.mockResolvedValue({ US: 95 })

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]).toMatchObject({
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 190,
      costBasisEur: 140,
      pnlEur: 50,
    })
    expect(result.current.data?.[0].pnlPercent).toBeCloseTo(35.714, 3)
  })

  it('keeps reconciled Groupama values without querying public prices', async () => {
    holdings.mockResolvedValue([
      {
        ticker: 'GES_FCPE_1',
        name: 'Groupama Sélection ISR',
        quantity: 8,
        averageBuyIn: 100,
        currentPrice: 125,
        quoteCurrency: 'EUR',
        currentValueEur: 1000,
        costBasisEur: 800,
        pnlEur: 200,
        pnlPercent: 25,
        priceUpdatedAt: '2026-07-26T08:00:00Z',
      },
    ])

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({
      ticker: 'GES_FCPE_1',
      currentPrice: 125,
      currentValueEur: 1000,
      pnlEur: 200,
    })
    expect(prices).not.toHaveBeenCalled()
  })
})

describe('usePortfolio', () => {
  beforeEach(() => {
    list.mockReset()
    holdings.mockReset()
    prices.mockReset()
  })

  it('expands PEE holdings instead of aggregating the plan as cash', async () => {
    list.mockResolvedValue([
      {
        id: 7,
        name: 'PEE Groupama',
        type: 'PEE',
        color: '#16a34a',
        currentBalanceEur: 1000,
      },
    ])
    holdings.mockResolvedValue([
      {
        ticker: 'GES_FCPE_1',
        name: 'Groupama Sélection ISR',
        quantity: 8,
        averageBuyIn: 100,
        currentPrice: 125,
        quoteCurrency: 'EUR',
        currentValueEur: 1000,
        costBasisEur: 800,
        pnlEur: 200,
        pnlPercent: 25,
        priceUpdatedAt: '2026-07-26T08:00:00Z',
      },
    ])

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([
      expect.objectContaining({
        accountType: 'PEE',
        ticker: 'GES_FCPE_1',
        valueEur: 1000,
      }),
    ])
    expect(holdings).toHaveBeenCalledWith(7)
    expect(prices).not.toHaveBeenCalled()
  })
})

describe('provider-side holding identifiers', () => {
  beforeEach(() => {
    priceHistory.mockReset()
    priceIntraday.mockReset()
    securityInsight.mockReset()
  })

  it('never sends Groupama identifiers to public history endpoints', () => {
    const wrapper = makeWrapper()
    const history = renderHook(() => usePriceHistory('GES_FCPE_1', 12, '1Y'), { wrapper })
    const intraday = renderHook(() => usePriceHistory('GES_FCPE_1', 1, '24H'), { wrapper })

    expect(history.result.current.fetchStatus).toBe('idle')
    expect(intraday.result.current.fetchStatus).toBe('idle')
    expect(priceHistory).not.toHaveBeenCalled()
    expect(priceIntraday).not.toHaveBeenCalled()
  })

  it('never sends Groupama identifiers to the public security-insight endpoint', () => {
    const { result } = renderHook(
      () => useSecurityInsight('GES_FCPE_1', 'Groupama Sélection ISR'),
      { wrapper: makeWrapper() },
    )

    expect(result.current.fetchStatus).toBe('idle')
    expect(securityInsight).not.toHaveBeenCalled()
  })
})
