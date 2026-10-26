// SPDX-License-Identifier: GPL-3.0-or-later
package logger

import "io"

func McrfQuiet() *Logger {
	log := New("MCRF MasterDnsVPN", "ERROR")
	log.consoleWriter = io.Discard
	log.color = false
	return log
}
