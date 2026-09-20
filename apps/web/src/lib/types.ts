import type { DataIdentity } from './data-identity'
export interface Track {
  id: string
  providerId: string
  title: string
  artist: string
  album: string
  duration: number
  coverUrl: string
  qualities: string[]
  canDownload: boolean
}
export interface Collection {
  id: string
  providerId: string
  title: string
  artist?: string
  description: string
  coverUrl: string
  trackCount: number
  playCount?: number
  category: string
  tracks?: Track[]
}
export interface UserPlaylist {
  id: string
  providerId: string
  title: string
  description: string
  coverUrl: string
  trackCount: number
  tracks?: Track[]
  createdAt: string
  updatedAt: string
}
export interface LibrarySummary {
  favoriteTracks: number
  favoritePlaylists: number
  history: number
  historyTracks: number
  historyPlaylists: number
  historyAudiobooks: number
  userPlaylists: number
}
export type HistoryKind = 'track' | 'playlist' | 'audiobook'
export interface HistoryEntry {
  track: Track
  kind: HistoryKind
  contextId?: string
  playedAt: number
  playCount: number
  completedCount: number
  skipCount: number
  listenedMs: number
}
export interface StorageInfo {
  totalBytes: number
  availableBytes: number
  freeBytes: number
}
export interface StorageStatus {
  authorized: boolean
  configured: boolean
  authorizedRoot?: string
  authorizedRoots?: string[]
  authorizationSource?: 'fnos' | 'environment' | 'none'
  path?: string
  capacity?: StorageInfo
  error?: string
}
export interface StorageDirectory {
  name: string
  path: string
}
export interface StorageDirectories {
  path: string
  parent: string
  root: string
  roots: string[]
  directories: StorageDirectory[]
  truncated: boolean
}
export interface Provider {
  id: string
  name: string
  description: string
  enabled: boolean
  isDemo: boolean
  status: string
  capabilities: Record<'search' | 'charts' | 'playlists' | 'recommendations' | 'play' | 'download', boolean>
}
export interface PlayInfo {
  trackId: string
  url: string
  mimeType: string
  expiresAt?: string
  direct: boolean
  quality?: string
  sourceId?: string
  attemptedSources?: string[]
  resolvedTrack?: Track
}
export interface Settings {
  fileNameFormat?: 'title-artist' | 'artist-title' | 'title'
  embedTags?: boolean
  autoSwitchSource?: boolean
  writeMetadata: boolean
  downloadRoot: string
  concurrency: number
  writeLyrics: boolean
  writeCover: boolean
  defaultQuality: string
  showDirect: boolean
}
export interface DownloadJob {
  fileNameFormat?: string
  writeLyrics?: boolean
  writeCover?: boolean
  embedTags?: boolean
  lyricsPath?: string
  coverPath?: string
  tagsWritten?: boolean
  writeMetadata?: boolean
  metadataPath?: string
  warning?: string
  id: string
  track: Track
  quality: string
  state: string
  bytesDone: number
  bytesTotal: number
  speed: number
  targetPath: string
  error?: string
  createdAt: string
  updatedAt: string
}
export interface SearchResult {
  page?: number
  pageSize?: number
  tracks: Track[]
  playlists: Collection[]
  total: number
  artists: { id: string; name: string; coverUrl: string; trackCount: number }[]
  albums: { id: string; title: string; artist: string; coverUrl: string; trackCount: number }[]
}
export interface Lyrics {
  lines: { time: number; text: string }[]
  source: string
}
export interface Session {
  dataIdentity?: DataIdentity
  authenticated: boolean
  required: boolean
  authMode?: 'fnos'
  username?: string
  loginMethod?: 'token' | 'password'
  deployMode?: 'nas' | 'cloud'
  error?: string
}

export interface DiscoveryFeed {
  tracks: Track[]
  playlists: Collection[]
  personalized: boolean
  reason: string
  unavailableSources?: string[]
  issues?: { sourceId: string; scope: 'tracks' | 'playlists' | 'new-tracks'; code: string }[]
}

export interface BookSection {
  id: string
  title: string
  items: Collection[]
}
export interface BookChannel {
  id: string
  title: string
  sections: BookSection[]
}
export interface BookRankTag {
  id: string
  name: string
}
export interface BookRankTab {
  id: string
  name: string
  tags: BookRankTag[]
}
export interface BookHome {
  channels: BookChannel[]
  ranks: BookRankTab[]
}
export interface BookRankResult {
  tab: BookRankTab
  tagId: string
  page: number
  pageSize: number
  total: number
  items: Collection[]
}
export interface BookAlbum extends Collection {
  page: number
  pageSize: number
  total: number
}
