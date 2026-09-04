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
