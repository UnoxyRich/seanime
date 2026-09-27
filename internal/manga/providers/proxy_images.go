package manga_providers

import (
	"context"
	util "seanime/internal/util/proxies"
)

func GetImageByProxy(url string, headers map[string]string) ([]byte, error) {
	return GetImageByProxyWithContext(context.Background(), url, headers)
}

func GetImageByProxyWithContext(ctx context.Context, url string, headers map[string]string) ([]byte, error) {
	ip := &util.ImageProxy{}
	image, _, err := ip.GetImageWithContext(ctx, url, headers)
	return image, err
}
