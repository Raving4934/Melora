import { act, cleanup, render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it } from 'vitest'
import { queryClient, SessionProvider, useCloudDeploy } from './api'
import type { Session } from './types'

function DeployProbe() {
  return <output data-testid="deploy-mode">{useCloudDeploy() ? 'cloud' : 'nas'}</output>
}

afterEach(() => {
  cleanup()
  queryClient.clear()
})

describe('SessionProvider / deploy context', () => {
  it('向应用树提供部署模式，调用方不需要重复订阅 auth/session', () => {
    const nasSession: Session = { authenticated: true, required: false, deployMode: 'nas' }
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <SessionProvider session={nasSession}>
          <DeployProbe />
        </SessionProvider>
      </QueryClientProvider>,
    )
    expect(screen.getByTestId('deploy-mode')).toHaveTextContent('nas')

    rerender(
      <QueryClientProvider client={queryClient}>
        <SessionProvider session={{ ...nasSession, deployMode: 'cloud' }}>
          <DeployProbe />
        </SessionProvider>
      </QueryClientProvider>,
    )
    expect(screen.getByTestId('deploy-mode')).toHaveTextContent('cloud')
  })

  it('Provider 外兼容已有 auth/session 缓存快照', () => {
    queryClient.setQueryData(['/auth/session'], {
      authenticated: true,
      required: false,
      deployMode: 'cloud',
    } satisfies Session)
    render(
      <QueryClientProvider client={queryClient}>
        <DeployProbe />
      </QueryClientProvider>,
    )
    expect(screen.getByTestId('deploy-mode')).toHaveTextContent('cloud')
  })

  it('Provider 外只响应 auth/session 部署模式变化', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DeployProbe />
      </QueryClientProvider>,
    )
    expect(screen.getByTestId('deploy-mode')).toHaveTextContent('nas')

    act(() => {
      queryClient.setQueryData(['/unrelated'], { value: 1 })
      queryClient.setQueryData(['/auth/session'], {
        authenticated: true,
        required: false,
        deployMode: 'cloud',
      } satisfies Session)
    })
    expect(screen.getByTestId('deploy-mode')).toHaveTextContent('cloud')
  })
})
