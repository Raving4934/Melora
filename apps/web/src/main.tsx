import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { queryClient } from './lib/api'
import './styles.css'
import { appBasePath } from './lib/base'
import { isImmersivePath, setDocumentSurface } from './lib/document-surface'
import { registerPWA } from './lib/pwa'
if (appBasePath) document.documentElement.dataset.host = 'fnos'
// 开发入口与生产入口保持一致；生产冷加载的首帧由Go在HTML中提前标记。
setDocumentSurface(isImmersivePath(window.location.pathname, appBasePath || '/'))
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename={appBasePath || '/'}>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
void registerPWA()
