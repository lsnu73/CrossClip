use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

fn sanitize_host_name(name: &str) -> String {
    let mut out = String::new();
    for c in name.chars() {
        if c.is_ascii_alphanumeric() || c == '-' {
            out.push(c);
        } else if c.is_ascii_whitespace() || c == '_' {
            out.push('-');
        }
    }
    while out.contains("--") {
        out = out.replace("--", "-");
    }
    let trimmed = out.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "crossclip".to_string()
    } else {
        trimmed.chars().take(63).collect()
    }
}

pub fn start_mdns_broadcast(device_name: &str, port: u16, device_id: &str) -> Option<ServiceDaemon> {
    let mdns = ServiceDaemon::new().ok()?;
    let service_type = "_crossclip._tcp.local.";
    let instance_name = device_name;
    let host_name = format!("{}.local.", sanitize_host_name(device_name));

    let mut properties = HashMap::new();
    properties.insert("device_id".to_string(), device_id.to_string());
    properties.insert("version".to_string(), env!("CARGO_PKG_VERSION").to_string());

    let lan_ips = crate::ip_util::get_local_lan_ips();
    let bind_ip = lan_ips.first().cloned().unwrap_or_else(|| "127.0.0.1".to_string());

    let my_service = ServiceInfo::new(
        service_type,
        instance_name,
        &host_name,
        &bind_ip,
        port,
        properties,
    )
    .ok()?;

    if mdns.register(my_service).is_ok() {
        log_ok!(
            "mDNS",
            "已发布局域网服务 (绑定 IP: {}): {}.{}",
            bind_ip,
            instance_name,
            service_type
        );
        Some(mdns)
    } else {
        log_warn!("mDNS", "注册局域网服务失败: {}.{}", instance_name, service_type);
        None
    }
}
