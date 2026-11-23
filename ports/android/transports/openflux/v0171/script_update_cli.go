// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"time"

	mobile "openflux-mobile"
)

// A private request beside the normal connection config selects its own store.
// Imported profiles cannot select directories, keys, URLs or native binaries.
func init() { stagedScriptUpdate = mcrfRunOfficialScriptUpdate }

func mcrfRunOfficialScriptUpdate(path string) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 4096 {
		return errors.New("private script request required")
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return errors.New("private script request changed")
	}
	var request struct {
		Action      string `json:"action"`
		ID          string `json:"id"`
		ExpectedSHA string `json:"expectedSha256"`
	}
	decoder := json.NewDecoder(io.LimitReader(f, 4097))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil {
		return errors.New("script request rejected")
	}
	var extra interface{}
	if decoder.Decode(&extra) != io.EOF {
		return errors.New("script request rejected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	go func() { _, _ = io.Copy(io.Discard, os.Stdin); cancel() }()
	var report interface{}
	if request.Action == "status" {
		if request.ID != "" || request.ExpectedSHA != "" {
			return errors.New("inventory request rejected")
		}
		report, err = mobile.McrfInspectOfficialScripts(ctx, path)
	} else {
		report, err = mobile.McrfUpdateOfficialScript(ctx, path, request.Action, request.ID, request.ExpectedSHA)
	}
	if err != nil {
		return err
	}
	data, err := json.Marshal(report)
	if err != nil {
		return err
	}
	fmt.Println("MCRF_SCRIPT " + string(data))
	return nil
}
