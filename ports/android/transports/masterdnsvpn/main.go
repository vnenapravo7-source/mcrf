// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/logger"
	"masterdnsvpn-go/internal/security"
)

func main() {
	if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" {
		fmt.Println("MCRF_ERROR MASTERDNS_CONFIG_ARGUMENTS")
		os.Exit(2)
	}
	file, err := os.Open(os.Args[2])
	if err != nil {
		fmt.Println("MCRF_ERROR MASTERDNS_CONFIG_READ")
		os.Exit(2)
	}
	settings, err := config.McrfRead(file)
	file.Close()
	if err != nil {
		fmt.Println("MCRF_ERROR " + err.Error())
		os.Exit(2)
	}
	cfg, err := config.McrfConfig(settings, filepath.Dir(os.Args[2]))
	if err != nil {
		code := "MASTERDNS_CONFIG_REJECTED"
		switch err.Error() {
		case "MASTERDNS_PRIVATE_DIRECTORY", "MASTERDNS_PRIVATE_RESOLVERS":
			code = err.Error()
		}
		fmt.Println("MCRF_ERROR " + code)
		os.Exit(2)
	}
	codec, err := security.NewCodec(cfg.DataEncryptionMethod, cfg.EncryptionKey)
	if err != nil {
		fmt.Println("MCRF_ERROR MASTERDNS_CODEC")
		os.Exit(2)
	}
	app := client.New(cfg, logger.McrfQuiet(), codec)
	if app.BuildConnectionMap() != nil {
		fmt.Println("MCRF_ERROR MASTERDNS_ENDPOINT_MAP")
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		cancel()
	}()
	done := make(chan error, 1)
	var stageMu sync.Mutex
	lastStage := ""
	go func() {
		done <- app.McrfRun(ctx, func(stage string) {
			stageMu.Lock()
			defer stageMu.Unlock()
			if stage == lastStage {
				return
			}
			lastStage = stage
			if stage == "ready" {
				fmt.Println("MCRF_READY")
			} else {
				fmt.Println("MCRF_STAGE MASTERDNS_" + strings.ToUpper(stage))
			}
		})
	}()
	select {
	case err = <-done:
		if err != nil && ctx.Err() == nil {
			fmt.Println("MCRF_ERROR " + err.Error())
			os.Exit(1)
		}
	case <-ctx.Done():
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			fmt.Println("MCRF_STAGE MASTERDNS_STOP_FORCED")
		}
	}
}
