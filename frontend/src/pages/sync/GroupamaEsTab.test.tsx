import "@testing-library/jest-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { GroupamaEsSessionStatus } from "@/types/api"

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock("@/lib/api-client", () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))
vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { GroupamaEsTab } = await import("./GroupamaEsTab")

const inactiveStatus: GroupamaEsSessionStatus = {
  isActive: false,
  expiresAt: null,
  syncStatus: "IDLE",
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function renderTab(onConnected?: () => void) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
  }
  render(<GroupamaEsTab onConnected={onConnected} />, { wrapper: Wrapper })
}

describe("GroupamaEsTab authentication flow", () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: inactiveStatus })
  })

  it("submits credentials then a sanitized Groupama OTP", async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === "/groupama-es/auth/initiate") {
        return Promise.resolve({
          data: {
            processId: "process-123",
            mfaRequired: true,
            mfaType: "OTP",
          },
        })
      }
      if (url === "/groupama-es/auth/complete") {
        return Promise.resolve({
          data: {
            ...inactiveStatus,
            isActive: true,
            syncStatus: "QUEUED",
          },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    fireEvent.change(await screen.findByLabelText("sync.groupamaEs.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.groupamaEs.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.groupamaEs.connect" })
    )

    fireEvent.change(
      await screen.findByLabelText("sync.groupamaEs.otpCode"),
      { target: { value: "12a34" } }
    )
    expect(screen.getByLabelText("sync.groupamaEs.otpCode")).toHaveValue("1234")
    fireEvent.click(
      screen.getByRole("button", { name: "sync.groupamaEs.validate" })
    )

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith("/groupama-es/auth/complete", {
        processId: "process-123",
        code: "1234",
      })
    )
  })

  it("closes the account wizard only after the background import succeeds", async () => {
    const onConnected = vi.fn()
    let serverStatus = inactiveStatus
    apiGet.mockImplementation(() => Promise.resolve({ data: serverStatus }))
    apiPost.mockImplementation((url: string) => {
      if (url === "/groupama-es/auth/initiate") {
        return Promise.resolve({
          data: {
            processId: "process-123",
            mfaRequired: true,
            mfaType: "OTP",
          },
        })
      }
      if (url === "/groupama-es/auth/complete") {
        serverStatus = {
          ...inactiveStatus,
          isActive: true,
          syncStatus: "SUCCESS",
          lastSyncCompletedAt: "2026-07-26T10:00:00Z",
        }
        return Promise.resolve({
          data: {
            ...inactiveStatus,
            isActive: true,
            syncStatus: "QUEUED",
          },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab(onConnected)
    fireEvent.change(await screen.findByLabelText("sync.groupamaEs.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.groupamaEs.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.groupamaEs.connect" })
    )
    fireEvent.change(
      await screen.findByLabelText("sync.groupamaEs.otpCode"),
      { target: { value: "123456" } }
    )
    fireEvent.click(
      screen.getByRole("button", { name: "sync.groupamaEs.validate" })
    )

    expect(onConnected).not.toHaveBeenCalled()
    await waitFor(() => expect(onConnected).toHaveBeenCalledOnce())
  })

  it("maps a portal action requirement and clears the password", async () => {
    apiPost.mockRejectedValue({
      response: {
        status: 422,
        data: {
          code: "ACTION_REQUIRED",
          detail: "Complete an action in the customer portal",
        },
      },
    })
    renderTab()
    fireEvent.change(await screen.findByLabelText("sync.groupamaEs.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.groupamaEs.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.groupamaEs.connect" })
    )

    expect(
      await screen.findByText("sync.groupamaEs.errors.actionRequired")
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "common.retry" }))
    expect(screen.getByLabelText("sync.groupamaEs.password")).toHaveValue("")
  })

  it("shows progress and reports a manual sync failure", async () => {
    apiGet.mockResolvedValue({
      data: { ...inactiveStatus, isActive: true },
    })
    let rejectSync!: (reason?: unknown) => void
    apiPost.mockImplementation((url: string) => {
      if (url === "/groupama-es/sync") {
        return new Promise((_resolve, reject) => {
          rejectSync = reject
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    fireEvent.click(
      await screen.findByRole("button", { name: "sync.groupamaEs.sync" })
    )

    expect(
      await screen.findByRole("button", {
        name: "sync.groupamaEs.syncing",
      })
    ).toBeDisabled()
    rejectSync({})
    expect(
      await screen.findByText("sync.groupamaEs.errors.serverError")
    ).toBeInTheDocument()
  })
})
