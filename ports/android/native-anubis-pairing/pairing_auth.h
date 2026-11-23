#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <string_view>

namespace anubis {

using TlsExporter = std::array<uint8_t, 64>;
using SpakeMessage = std::array<uint8_t, 32>;
using PairingKey = std::array<uint8_t, 16>;
using ExchangeSpake = std::function<bool(const SpakeMessage&, SpakeMessage&)>;
constexpr size_t kMaxPublicKeySize = 1024;
constexpr size_t kPeerInfoSize = 8192;
using EncryptedPeerInfo = std::array<uint8_t, kPeerInfoSize + 16>;
using ExchangePeerInfo = std::function<bool(const EncryptedPeerInfo&, EncryptedPeerInfo&)>;

// The exchange callback must enforce its transport deadline. A derived key is
// provisional until an authenticated peer-info frame has been opened with it.
bool DerivePairingKey(std::string_view code, const TlsExporter& exporter,
                      const ExchangeSpake& exchange, PairingKey& key);

bool VerifyPairing(std::string_view code, const TlsExporter& exporter,
                   std::string_view client_public_key, const ExchangeSpake& exchange_spake,
                   const ExchangePeerInfo& exchange_peer_info);

}  // namespace anubis
