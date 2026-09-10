use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use rand::RngCore;
use sha2::{Digest, Sha256};

pub fn compute_hash(text: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    format!("{:x}", hasher.finalize())
}

fn derive_key(key_str: &str) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(key_str.as_bytes());
    let result = hasher.finalize();
    let mut key = [0u8; 32];
    key.copy_from_slice(&result);
    key
}

#[allow(dead_code)]
pub fn encrypt(text: &str, key_str: &str) -> Result<String, String> {
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;

    let mut iv = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);

    let ciphertext = cipher
        .encrypt(nonce, text.as_bytes())
        .map_err(|e| e.to_string())?;

    let mut combined = Vec::with_capacity(iv.len() + ciphertext.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ciphertext);

    Ok(BASE64.encode(combined))
}

pub fn decrypt(encrypted_b64: &str, key_str: &str) -> Result<String, String> {
    let raw = BASE64.decode(encrypted_b64).map_err(|e| e.to_string())?;
    if raw.len() < 12 {
        return Err("Ciphertext too short".to_string());
    }

    let (iv, ciphertext) = raw.split_at(12);
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;
    let nonce = Nonce::from_slice(iv);

    let decrypted = cipher
        .decrypt(nonce, ciphertext)
        .map_err(|e| e.to_string())?;

    String::from_utf8(decrypted).map_err(|e| e.to_string())
}

/// 加密任意二进制数据（用于文件分块传输）
pub fn encrypt_bytes(data: &[u8], key_str: &str) -> Result<String, String> {
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;

    let mut iv = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);

    let ciphertext = cipher
        .encrypt(nonce, data)
        .map_err(|e| e.to_string())?;

    let mut combined = Vec::with_capacity(iv.len() + ciphertext.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ciphertext);

    Ok(BASE64.encode(combined))
}

/// 解密任意二进制数据（用于文件分块传输）
pub fn decrypt_bytes(encrypted_b64: &str, key_str: &str) -> Result<Vec<u8>, String> {
    let raw = BASE64.decode(encrypted_b64).map_err(|e| e.to_string())?;
    if raw.len() < 12 {
        return Err("Ciphertext too short".to_string());
    }

    let (iv, ciphertext) = raw.split_at(12);
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;
    let nonce = Nonce::from_slice(iv);

    let decrypted = cipher
        .decrypt(nonce, ciphertext)
        .map_err(|e| e.to_string())?;

    Ok(decrypted)
}

/// 计算任意字节数据的 SHA-256 哈希
pub fn compute_hash_bytes(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}

// ==================== 二进制分块传输专用（免 Base64，省 33% 传输量） ====================

/// 加密二进制数据并**直接返回字节数组**（不做 Base64 编码）。
///
/// 返回结构：`nonce(12B) || ciphertext || tag(16B)`，与 `encrypt_bytes` 的字节布局一致，
/// 区别仅在于不做 Base64，供文件分块以二进制 body 直接传输。
pub fn encrypt_bytes_raw(data: &[u8], key_str: &str) -> Result<Vec<u8>, String> {
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;

    let mut iv = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);

    let ciphertext = cipher
        .encrypt(nonce, data)
        .map_err(|e| e.to_string())?;

    let mut combined = Vec::with_capacity(iv.len() + ciphertext.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ciphertext);
    Ok(combined)
}

/// 从二进制密文（`nonce||ciphertext||tag`）解密出原始字节，与 `encrypt_bytes_raw` 配对。
pub fn decrypt_bytes_raw(payload: &[u8], key_str: &str) -> Result<Vec<u8>, String> {
    // 至少需要 12 字节 nonce + 16 字节 GCM tag
    if payload.len() < 28 {
        return Err(format!("Ciphertext too short: {}", payload.len()));
    }

    let (iv, ciphertext) = payload.split_at(12);
    let key = derive_key(key_str);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;
    let nonce = Nonce::from_slice(iv);

    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|e| e.to_string())
}

/// 流式计算文件的 SHA-256 哈希（按 64KB 分块读取，避免把大文件整体载入内存）
pub fn compute_file_hash(path: &std::path::Path) -> Result<String, String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer).map_err(|e| e.to_string())?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
