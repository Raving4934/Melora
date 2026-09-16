package model

// 公共数据契约供 API、Provider 和下载 Worker 共享。
type Track struct {
	ID          string   `json:"id"`
	ProviderID  string   `json:"providerId"`
	Title       string   `json:"title"`
	Artist      string   `json:"artist"`
	Album       string   `json:"album"`
	Duration    int      `json:"duration"`
	CoverURL    string   `json:"coverUrl"`
	Qualities   []string `json:"qualities"`
	CanDownload bool     `json:"canDownload"`
}
type Collection struct {
	ID          string  `json:"id"`
	ProviderID  string  `json:"providerId"`
	Title       string  `json:"title"`
	Artist      string  `json:"artist,omitempty"`
	Description string  `json:"description"`
	CoverURL    string  `json:"coverUrl"`
	TrackCount  int     `json:"trackCount"`
	PlayCount   *int64  `json:"playCount,omitempty"`
	Category    string  `json:"category"`
	Tracks      []Track `json:"tracks,omitempty"`
}
type UserPlaylist struct {
	ID          string  `json:"id"`
	ProviderID  string  `json:"providerId"`
	Title       string  `json:"title"`
	Description string  `json:"description"`
	CoverURL    string  `json:"coverUrl"`
	TrackCount  int     `json:"trackCount"`
	Tracks      []Track `json:"tracks,omitempty"`
	CreatedAt   string  `json:"createdAt"`
	UpdatedAt   string  `json:"updatedAt"`
}
type PlayInfo struct {
	TrackID          string   `json:"trackId"`
	URL              string   `json:"url"`
	MIMEType         string   `json:"mimeType"`
	ExpiresAt        string   `json:"expiresAt,omitempty"`
	Direct           bool     `json:"direct"`
	Quality          string   `json:"quality,omitempty"`
	SourceID         string   `json:"sourceId,omitempty"`
	AttemptedSources []string `json:"attemptedSources,omitempty"`
	ResolvedTrack    *Track   `json:"resolvedTrack,omitempty"`
}
type LibrarySummary struct {
	FavoriteTracks    int `json:"favoriteTracks"`
	FavoritePlaylists int `json:"favoritePlaylists"`
	History           int `json:"history"`
	HistoryTracks     int `json:"historyTracks"`
	HistoryPlaylists  int `json:"historyPlaylists"`
	HistoryAudiobooks int `json:"historyAudiobooks"`
	UserPlaylists     int `json:"userPlaylists"`
}

// 播放历史来源：单曲、歌单/榜单、听书专辑；旧记录默认 track。
const (
	HistoryKindTrack     = "track"
	HistoryKindPlaylist  = "playlist"
	HistoryKindAudiobook = "audiobook"
)

// HistoryKindValid 只接受已声明的来源，防止客户端写入任意分类。
func HistoryKindValid(kind string) bool {
	switch kind {
	case HistoryKindTrack, HistoryKindPlaylist, HistoryKindAudiobook:
		return true
	}
	return false
}

type HistoryEntry struct {
	Track          Track  `json:"track"`
	Kind           string `json:"kind"`
	ContextID      string `json:"contextId,omitempty"`
	PlayedAt       int64  `json:"playedAt"`
	PlayCount      int    `json:"playCount"`
	CompletedCount int    `json:"completedCount"`
	SkipCount      int    `json:"skipCount"`
	ListenedMs     int64  `json:"listenedMs"`
}
type DownloadJob struct {
	FileNameFormat string `json:"fileNameFormat,omitempty"`
	WriteLyrics    bool   `json:"writeLyrics"`
	WriteCover     bool   `json:"writeCover"`
	EmbedTags      bool   `json:"embedTags"`
	LyricsPath     string `json:"lyricsPath,omitempty"`
	CoverPath      string `json:"coverPath,omitempty"`
	TagsWritten    bool   `json:"tagsWritten"`
	WriteMetadata  bool   `json:"writeMetadata"`
	MetadataPath   string `json:"metadataPath,omitempty"`
	Warning        string `json:"warning,omitempty"`
	ID             string `json:"id"`
	Track          Track  `json:"track"`
	Quality        string `json:"quality"`
	State          string `json:"state"`
	BytesDone      int64  `json:"bytesDone"`
	BytesTotal     int64  `json:"bytesTotal"`
	Speed          int64  `json:"speed"`
	TargetPath     string `json:"targetPath"`
	Error          string `json:"error,omitempty"`
	CreatedAt      string `json:"createdAt"`
	UpdatedAt      string `json:"updatedAt"`
}
type Settings struct {
	FileNameFormat   string `json:"fileNameFormat"`
	EmbedTags        bool   `json:"embedTags"`
	AutoSwitchSource bool   `json:"autoSwitchSource"`
	WriteMetadata    bool   `json:"writeMetadata"`
	DownloadRoot     string `json:"downloadRoot"`
	Concurrency      int    `json:"concurrency"`
	WriteLyrics      bool   `json:"writeLyrics"`
	WriteCover       bool   `json:"writeCover"`
	DefaultQuality   string `json:"defaultQuality"`
	ShowDirect       bool   `json:"showDirect"`
}

// CatalogMetadata 只保存目录返回的曲目身份与LX参数，绝不接收解析后的媒体URL或脚本凭据。
type CatalogMetadata struct {
	Track     Track          `json:"track"`
	MusicInfo map[string]any `json:"musicInfo,omitempty"`
}
