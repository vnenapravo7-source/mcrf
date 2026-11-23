#include "pairing_auth.h"

#include <jni.h>

#include <openssl/crypto.h>

namespace {

template <size_t Size>
struct SecretBytes {
    std::array<uint8_t, Size> bytes{};
    ~SecretBytes() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

template <size_t Size>
bool Exchange(JNIEnv* env, jobject exchange, jmethodID method, jint type,
              const std::array<uint8_t, Size>& request, std::array<uint8_t, Size>& response) {
    auto request_array = env->NewByteArray(Size);
    if (!request_array) return false;
    env->SetByteArrayRegion(request_array, 0, Size, reinterpret_cast<const jbyte*>(request.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(request_array);
        return false;
    }
    auto response_array = static_cast<jbyteArray>(env->CallObjectMethod(exchange, method, type, request_array));
    if (env->ExceptionCheck()) response_array = nullptr;
    bool accepted = response_array && env->GetArrayLength(response_array) == static_cast<jsize>(Size);
    if (accepted) {
        env->GetByteArrayRegion(response_array, 0, Size, reinterpret_cast<jbyte*>(response.data()));
        accepted = !env->ExceptionCheck();
    }
    if (response_array) env->DeleteLocalRef(response_array);
    env->DeleteLocalRef(request_array);
    return accepted;
}

}  // namespace

// exchangeFrame(int, byte[]) returns the exact-size payload or null. The
// Android caller owns the TLS socket, framing, deadline, and cancellation.
extern "C" JNIEXPORT jboolean JNICALL Java_org_mcrf_client_AnubisPairingNative_pair(
        JNIEnv* env, jobject, jbyteArray code_array, jbyteArray exporter_array,
        jbyteArray public_key_array, jobject exchange) {
    if (!code_array || !exporter_array || !public_key_array || !exchange) return JNI_FALSE;
    SecretBytes<6> code;
    SecretBytes<anubis::TlsExporter{}.size()> exporter;
    const jsize key_size = env->GetArrayLength(public_key_array);
    if (env->GetArrayLength(code_array) != static_cast<jsize>(code.bytes.size()) ||
        env->GetArrayLength(exporter_array) != static_cast<jsize>(exporter.bytes.size()) || key_size <= 0 ||
        key_size > static_cast<jsize>(anubis::kMaxPublicKeySize)) return JNI_FALSE;

    std::array<char, anubis::kMaxPublicKeySize> public_key{};
    env->GetByteArrayRegion(code_array, 0, code.bytes.size(), reinterpret_cast<jbyte*>(code.bytes.data()));
    env->GetByteArrayRegion(exporter_array, 0, exporter.bytes.size(), reinterpret_cast<jbyte*>(exporter.bytes.data()));
    env->GetByteArrayRegion(public_key_array, 0, key_size, reinterpret_cast<jbyte*>(public_key.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;

    const jclass exchange_type = env->GetObjectClass(exchange);
    if (!exchange_type) return JNI_FALSE;
    const jmethodID exchange_frame = env->GetMethodID(exchange_type, "exchangeFrame", "(I[B)[B");
    env->DeleteLocalRef(exchange_type);
    if (!exchange_frame) return JNI_FALSE;
    const auto code_text = std::string_view(reinterpret_cast<const char*>(code.bytes.data()), code.bytes.size());
    try {
        return anubis::VerifyPairing(code_text, exporter.bytes,
                std::string_view(public_key.data(), static_cast<size_t>(key_size)),
                [&](const anubis::SpakeMessage& request, anubis::SpakeMessage& response) {
                    return Exchange(env, exchange, exchange_frame, 0, request, response);
                },
                [&](const anubis::EncryptedPeerInfo& request, anubis::EncryptedPeerInfo& response) {
                    return Exchange(env, exchange, exchange_frame, 1, request, response);
                }) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
