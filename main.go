package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	// 1. Read the connection string from the environment.
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		log.Fatal("DATABASE_URL is not set, e.g. postgres://journal:journal@localhost:5432/journal")
	}

	// 2. A context that gives up after 5 seconds.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 3. Create the connection pool.
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		log.Fatalf("could not create connection pool: %v", err)
	}
	defer pool.Close()

	// 4. Check the database is actually reachable.
	if err := pool.Ping(ctx); err != nil {
		log.Fatalf("could not reach database: %v", err)
	}

	// 5. Count the entries.
	var count int
	err = pool.QueryRow(ctx, "SELECT count(*) FROM entries").Scan(&count)
	if err != nil {
		log.Fatalf("could not count entries: %v", err)
	}

	// 6. Print the result.
	fmt.Printf("entries in database: %d\n", count)
}