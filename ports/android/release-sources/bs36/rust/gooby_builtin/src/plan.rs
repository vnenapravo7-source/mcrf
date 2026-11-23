#[derive(Debug, Clone)]
pub struct TransformPlan {
    pub chunks: Vec<Vec<u8>>,
    pub low_ttl_first: bool,
    pub delay_ms: u64,
}

impl TransformPlan {
    pub fn passthrough(data: &[u8]) -> Self {
        Self {
            chunks: vec![data.to_vec()],
            low_ttl_first: false,
            delay_ms: 0,
        }
    }
}

