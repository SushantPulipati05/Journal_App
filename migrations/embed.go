// Package migrations bundles the SQL migration files into the Go binary.
package migrations

import "embed"

//go:embed *.sql
var FS embed.FS