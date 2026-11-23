#include "pairing_auth.h"

#include <algorithm>
#include <memory>

#include <openssl/aead.h>
#include <openssl/crypto.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/hkdf.h>

namespace anubis {
namespace {

// Protocol constants from AOSP packages/modules/adb/pairing_auth.
constexpr uint8_t kClientName[] = "adb pair client";
constexpr uint8_t kServerName[] = "adb pair server";
constexpr uint8_t kKeyInfo[] = "adb pairing_auth aes-128-gcm key";
constexpr uint8_t kAdbRsaPublicKey = 0;
constexpr uint8_t kAdbDeviceGuid = 1;

bool PrintableAscii(uint8_t value) {
    return value >= 0x20 && value <= 0x7e;
}

template <size_t Size>
struct SecretBytes {
    std::array<uint8_t, Size> bytes{};
    ~SecretBytes() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

}  // namespace

bool DerivePairingKey(std::string_view code, const TlsExporter& exporter,
                      const ExchangeSpake& exchange, PairingKey& key) {
    OPENSSL_cleanse(key.data(), key.size());
    if (code.size() != 6 || !std::all_of(code.begin(), code.end(), [](char value) {
            return value >= '0' && value <= '9';
        }) || !exchange) {
        return false;
    }

    std::unique_ptr<SPAKE2_CTX, decltype(&SPAKE2_CTX_free)> spake(
            SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName),
                           kServerName, sizeof(kServerName)), SPAKE2_CTX_free);
    if (!spake) return false;

    SpakeMessage local{};
    size_t local_size = 0;
    {
        SecretBytes<6 + TlsExporter{}.size()> password;
        std::copy(code.begin(), code.end(), password.bytes.begin());
        std::copy(exporter.begin(), exporter.end(), password.bytes.begin() + code.size());
        if (!SPAKE2_generate_msg(spake.get(), local.data(), &local_size, local.size(),
                                 password.bytes.data(), password.bytes.size()) || local_size != local.size()) {
            return false;
        }
    }

    SpakeMessage peer{};
    if (!exchange(local, peer)) return false;

    SecretBytes<SPAKE2_MAX_KEY_SIZE> shared;
    size_t shared_size = 0;
    if (!SPAKE2_process_msg(spake.get(), shared.bytes.data(), &shared_size, shared.bytes.size(),
                            peer.data(), peer.size()) || shared_size == 0) {
        return false;
    }
    SecretBytes<PairingKey{}.size()> derived;
    if (!HKDF(derived.bytes.data(), derived.bytes.size(), EVP_sha256(), shared.bytes.data(), shared_size,
              nullptr, 0, kKeyInfo, sizeof(kKeyInfo) - 1)) {
        return false;
    }
    key = derived.bytes;
    return true;
}

bool VerifyPairing(std::string_view code, const TlsExporter& exporter,
                   std::string_view client_public_key, const ExchangeSpake& exchange_spake,
                   const ExchangePeerInfo& exchange_peer_info) {
    if (client_public_key.empty() || client_public_key.size() > kMaxPublicKeySize ||
        !std::all_of(client_public_key.begin(), client_public_key.end(), PrintableAscii) ||
        !exchange_peer_info) return false;

    SecretBytes<PairingKey{}.size()> key;
    if (!DerivePairingKey(code, exporter, exchange_spake, key.bytes)) return false;
    std::unique_ptr<EVP_AEAD_CTX, decltype(&EVP_AEAD_CTX_free)> aead(
            EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key.bytes.data(), key.bytes.size(), 16),
            EVP_AEAD_CTX_free);
    if (!aead) return false;

    // ADB uses sequence zero for the single peer-info record in each direction.
    const std::array<uint8_t, 12> nonce{};
    std::array<uint8_t, kPeerInfoSize> public_info{};
    public_info[0] = kAdbRsaPublicKey;
    std::copy(client_public_key.begin(), client_public_key.end(), public_info.begin() + 1);
    EncryptedPeerInfo request{};
    size_t sealed_size = 0;
    if (!EVP_AEAD_CTX_seal(aead.get(), request.data(), &sealed_size, request.size(),
                           nonce.data(), nonce.size(), public_info.data(), public_info.size(), nullptr, 0) ||
        sealed_size != request.size()) return false;

    EncryptedPeerInfo response{};
    if (!exchange_peer_info(request, response)) return false;
    std::array<uint8_t, kPeerInfoSize> peer_info{};
    size_t opened_size = 0;
    if (!EVP_AEAD_CTX_open(aead.get(), peer_info.data(), &opened_size, peer_info.size(),
                           nonce.data(), nonce.size(), response.data(), response.size(), nullptr, 0) ||
        opened_size != peer_info.size() || peer_info[0] != kAdbDeviceGuid) return false;

    const auto guid_start = peer_info.begin() + 1;
    const auto guid_end = std::find(guid_start, peer_info.end(), 0);
    return guid_end != guid_start && guid_end != peer_info.end() &&
           std::all_of(guid_start, guid_end, PrintableAscii);
}

}  // namespace anubis
