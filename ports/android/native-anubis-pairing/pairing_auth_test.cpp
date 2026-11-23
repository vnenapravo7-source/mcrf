#include "pairing_auth.h"

#include <algorithm>
#include <memory>

#include <openssl/aead.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/hkdf.h>

namespace {

anubis::ExchangeSpake Responder(std::string_view server_code, const anubis::TlsExporter& exporter,
                                anubis::PairingKey& server_key) {
    static constexpr uint8_t kClient[] = "adb pair client";
    static constexpr uint8_t kServer[] = "adb pair server";
    static constexpr uint8_t kInfo[] = "adb pairing_auth aes-128-gcm key";
    return [server_code, &exporter, &server_key](const anubis::SpakeMessage& client, anubis::SpakeMessage& server) {
        std::unique_ptr<SPAKE2_CTX, decltype(&SPAKE2_CTX_free)> peer(
                SPAKE2_CTX_new(spake2_role_bob, kServer, sizeof(kServer), kClient, sizeof(kClient)),
                SPAKE2_CTX_free);
        if (!peer) return false;
        std::array<uint8_t, 70> password{};
        std::copy(server_code.begin(), server_code.end(), password.begin());
        std::copy(exporter.begin(), exporter.end(), password.begin() + server_code.size());
        size_t message_size = 0;
        std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> secret{};
        size_t secret_size = 0;
        return SPAKE2_generate_msg(peer.get(), server.data(), &message_size, server.size(),
                                  password.data(), password.size()) && message_size == server.size() &&
               SPAKE2_process_msg(peer.get(), secret.data(), &secret_size, secret.size(), client.data(), client.size()) &&
               HKDF(server_key.data(), server_key.size(), EVP_sha256(), secret.data(), secret_size,
                    nullptr, 0, kInfo, sizeof(kInfo) - 1) == 1;
    };
}

bool DeriveAgainstResponder(std::string_view client_code, std::string_view server_code, bool expect_match) {
    anubis::TlsExporter exporter{};
    for (size_t i = 0; i < exporter.size(); ++i) exporter[i] = static_cast<uint8_t>(i);
    anubis::PairingKey client_key{};
    anubis::PairingKey server_key{};
    const bool derived = anubis::DerivePairingKey(client_code, exporter,
                                                 Responder(server_code, exporter, server_key), client_key);
    return derived && (client_key == server_key) == expect_match;
}

enum class PeerResult { Valid, WrongType, Tampered, Reflected };

bool VerifyPeerInfo(PeerResult result, std::string_view server_code) {
    anubis::TlsExporter exporter{};
    anubis::PairingKey server_key{};
    return anubis::VerifyPairing("012345", exporter, "test-key Anubis",
            Responder(server_code, exporter, server_key),
            [&](const anubis::EncryptedPeerInfo& request, anubis::EncryptedPeerInfo& response) {
        if (result == PeerResult::Reflected) {
            response = request;
            return true;
        }
        std::unique_ptr<EVP_AEAD_CTX, decltype(&EVP_AEAD_CTX_free)> aead(
                EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), server_key.data(), server_key.size(), 16),
                EVP_AEAD_CTX_free);
        std::array<uint8_t, anubis::kPeerInfoSize> peer{};
        peer[0] = result == PeerResult::WrongType ? 2 : 1;
        const std::string_view guid = "fixture-guid";
        std::copy(guid.begin(), guid.end(), peer.begin() + 1);
        const std::array<uint8_t, 12> nonce{};
        size_t sealed_size = 0;
        if (!aead || !EVP_AEAD_CTX_seal(aead.get(), response.data(), &sealed_size, response.size(),
                                       nonce.data(), nonce.size(), peer.data(), peer.size(), nullptr, 0) ||
            sealed_size != response.size()) return false;
        if (result == PeerResult::Tampered) response.back() ^= 1;
        return true;
    });
}

bool RejectsInvalidAndCanceledExchange() {
    anubis::TlsExporter exporter{};
    anubis::PairingKey output{};
    output.fill(0xff);
    int calls = 0;
    const auto cancel_exchange = [&](const anubis::SpakeMessage&, anubis::SpakeMessage&) {
        ++calls;
        return false;
    };
    return !anubis::DerivePairingKey("12x456", exporter, cancel_exchange, output) && calls == 0 &&
           output == anubis::PairingKey{} &&
           !anubis::DerivePairingKey("012345", exporter, cancel_exchange, output) && calls == 1 &&
           output == anubis::PairingKey{};
}

}  // namespace

int main() {
    return DeriveAgainstResponder("012345", "012345", true) &&
           DeriveAgainstResponder("012345", "654321", false) &&
           RejectsInvalidAndCanceledExchange() &&
           VerifyPeerInfo(PeerResult::Valid, "012345") &&
           !VerifyPeerInfo(PeerResult::Valid, "654321") &&
           !VerifyPeerInfo(PeerResult::Tampered, "012345") &&
           !VerifyPeerInfo(PeerResult::WrongType, "012345") &&
           !VerifyPeerInfo(PeerResult::Reflected, "012345") ? 0 : 1;
}
