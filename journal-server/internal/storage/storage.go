// Package storage keeps the photo files in S3-compatible object storage
// (Backblaze B2; Cloudflare R2 or AWS S3 work the same way).
//
// Devices never get the storage keys. The server hands out short-lived
// "presigned" links instead: one link lets a device upload exactly one file,
// another lets it download exactly one file, and both expire in minutes.
// The bucket itself stays private.
package storage

import (
	"context"
	"errors"
	"net/url"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ErrNotFound means there is no file at that key (e.g. it was never uploaded).
var ErrNotFound = errors.New("object not found")

// ObjectInfo is what the server checks about an uploaded file.
type ObjectInfo struct {
	Size        int64
	ContentType string
}

// Store is everything the server needs from object storage. An interface, so
// tests can use an in-memory fake instead of a real bucket.
type Store interface {
	PresignPut(ctx context.Context, key string, ttl time.Duration) (string, error)
	PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error)
	Stat(ctx context.Context, key string) (ObjectInfo, error)
	Delete(ctx context.Context, key string) error
}

// S3 talks to any S3-compatible bucket.
type S3 struct {
	client *minio.Client
	bucket string
}

// NewS3 connects to a bucket.
//
//	Backblaze B2:   endpoint "s3.us-west-004.backblazeb2.com", region "us-west-004"
//	                (both shown on the bucket's page), keys from "Application Keys".
//	Cloudflare R2:  endpoint "<account id>.r2.cloudflarestorage.com", region "auto".
func NewS3(endpoint, region, accessKeyID, secretAccessKey, bucket string) (*S3, error) {
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKeyID, secretAccessKey, ""),
		Secure: true,
		Region: region, // set explicitly, which also skips a lookup call
	})
	if err != nil {
		return nil, err
	}
	return &S3{client: client, bucket: bucket}, nil
}

func (r *S3) PresignPut(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := r.client.PresignedPutObject(ctx, r.bucket, key, ttl)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func (r *S3) PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := r.client.PresignedGetObject(ctx, r.bucket, key, ttl, url.Values{})
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func (r *S3) Stat(ctx context.Context, key string) (ObjectInfo, error) {
	info, err := r.client.StatObject(ctx, r.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		if minio.ToErrorResponse(err).Code == "NoSuchKey" {
			return ObjectInfo{}, ErrNotFound
		}
		return ObjectInfo{}, err
	}
	return ObjectInfo{Size: info.Size, ContentType: info.ContentType}, nil
}

func (r *S3) Delete(ctx context.Context, key string) error {
	return r.client.RemoveObject(ctx, r.bucket, key, minio.RemoveObjectOptions{})
}
