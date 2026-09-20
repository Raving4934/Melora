export interface LXSource {
  id: string
  name: string
  filename: string
  version: string
  author: string
  description: string
  status: 'checking' | 'ready' | 'error'
  error?: string
  allowHTTPHosts: string[]
  platforms: Record<string, { name: string; type: string; actions: string[]; qualitys: string[] | null }>
}

export interface SourcesState {
  items: LXSource[]
  activeSourceId: string
  available?: boolean
  catalogs?: string[]
  demoMode?: boolean
}
