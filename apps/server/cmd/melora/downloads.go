package main

import (
	"errors"
	"melora/internal/download"
	"melora/internal/model"
)

// 下载是可选能力。仅存储失效可降级；数据库失败、队列损坏等仍然阻止错误启动。
func initializeDownloads(settings model.Settings, resolve download.Resolver, persist download.Persister, jobs []model.DownloadJob, save func(model.Settings) error) (*download.Manager, model.Settings, error) {
	manager, err := download.New(settings.DownloadRoot, settings.Concurrency, resolve, persist, jobs)
	if !errors.Is(err, download.ErrStorageUnavailable) {
		return manager, settings, err
	}
	settings.DownloadRoot = ""
	if err = save(settings); err != nil {
		return nil, settings, err
	}
	manager, err = download.New("", settings.Concurrency, resolve, persist, jobs)
	return manager, settings, err
}
