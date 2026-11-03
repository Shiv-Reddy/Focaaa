use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use openmls::prelude::{tls_codec::*, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::{MemoryStorage, OpenMlsRustCrypto, RustCrypto};
use openmls_traits::OpenMlsProvider;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};

const SNAPSHOT_MAGIC: &[u8; 8] = b"FOCAAMLS";
const SNAPSHOT_VERSION: u32 = 1;
const MAX_SNAPSHOT_BYTES: usize = 16 * 1024 * 1024;
const MAX_SNAPSHOT_RECORDS: usize = 16_384;
const MAX_RECORD_KEY_BYTES: usize = 1024 * 1024;
const MAX_RECORD_VALUE_BYTES: usize = 4 * 1024 * 1024;

/// A provider adapter that keeps OpenMLS's typed key/value records in an importable/exportable map.
/// It is a persistence seam only: callers must encrypt the returned snapshot before storing it.
#[derive(Default)]
struct SnapshotProvider {
    crypto: RustCrypto,
    storage: MemoryStorage,
}

impl OpenMlsProvider for SnapshotProvider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = MemoryStorage;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }
    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }
    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}

struct DecodedSnapshot {
    group_id: GroupId,
    epoch: u64,
    records: HashMap<Vec<u8>, Vec<u8>>,
}

fn read_u32(input: &[u8], offset: &mut usize) -> Result<u32, &'static str> {
    let end = offset.checked_add(4).ok_or("snapshot_length_overflow")?;
    let bytes: [u8; 4] = input
        .get(*offset..end)
        .ok_or("snapshot_truncated")?
        .try_into()
        .map_err(|_| "snapshot_truncated")?;
    *offset = end;
    Ok(u32::from_be_bytes(bytes))
}

fn read_u64(input: &[u8], offset: &mut usize) -> Result<u64, &'static str> {
    let end = offset.checked_add(8).ok_or("snapshot_length_overflow")?;
    let bytes: [u8; 8] = input
        .get(*offset..end)
        .ok_or("snapshot_truncated")?
        .try_into()
        .map_err(|_| "snapshot_truncated")?;
    *offset = end;
    Ok(u64::from_be_bytes(bytes))
}

fn decode_snapshot(input: &[u8]) -> Result<DecodedSnapshot, &'static str> {
    if input.len() > MAX_SNAPSHOT_BYTES {
        return Err("snapshot_too_large");
    }
    let mut offset = 0;
    if input.get(..SNAPSHOT_MAGIC.len()) != Some(SNAPSHOT_MAGIC) {
        return Err("snapshot_bad_magic");
    }
    offset += SNAPSHOT_MAGIC.len();
    if read_u32(input, &mut offset)? != SNAPSHOT_VERSION {
        return Err("snapshot_unsupported_version");
    }
    let group_len = read_u32(input, &mut offset)? as usize;
    if group_len == 0 || group_len > 1024 {
        return Err("snapshot_invalid_group_id_length");
    }
    let group_end = offset
        .checked_add(group_len)
        .ok_or("snapshot_length_overflow")?;
    let group_id = GroupId::tls_deserialize(
        &mut input
            .get(offset..group_end)
            .ok_or("snapshot_truncated")?
            .as_ref(),
    )
    .map_err(|_| "snapshot_invalid_group_id")?;
    offset = group_end;
    let epoch = read_u64(input, &mut offset)?;
    let count = read_u32(input, &mut offset)? as usize;
    if count == 0 || count > MAX_SNAPSHOT_RECORDS {
        return Err("snapshot_invalid_record_count");
    }
    let mut records = HashMap::with_capacity(count);
    for _ in 0..count {
        let key_len = read_u32(input, &mut offset)? as usize;
        let value_len = read_u32(input, &mut offset)? as usize;
        if key_len == 0 || key_len > MAX_RECORD_KEY_BYTES || value_len > MAX_RECORD_VALUE_BYTES {
            return Err("snapshot_record_too_large");
        }
        let key_end = offset
            .checked_add(key_len)
            .ok_or("snapshot_length_overflow")?;
        let key = input
            .get(offset..key_end)
            .ok_or("snapshot_truncated")?
            .to_vec();
        offset = key_end;
        let value_end = offset
            .checked_add(value_len)
            .ok_or("snapshot_length_overflow")?;
        let value = input
            .get(offset..value_end)
            .ok_or("snapshot_truncated")?
            .to_vec();
        offset = value_end;
        if records.insert(key, value).is_some() {
            return Err("snapshot_duplicate_record");
        }
    }
    if offset != input.len() {
        return Err("snapshot_trailing_bytes");
    }
    Ok(DecodedSnapshot {
        group_id,
        epoch,
        records,
    })
}

fn encode_snapshot(
    group_id: &GroupId,
    epoch: u64,
    storage: &MemoryStorage,
) -> Result<Vec<u8>, &'static str> {
    let group_bytes = group_id
        .tls_serialize_detached()
        .map_err(|_| "snapshot_group_id_encode_failed")?;
    let values = storage
        .values
        .read()
        .map_err(|_| "snapshot_storage_lock_failed")?;
    if values.is_empty() || values.len() > MAX_SNAPSHOT_RECORDS {
        return Err("snapshot_invalid_record_count");
    }
    let mut records: Vec<_> = values.iter().collect();
    records.sort_by(|left, right| left.0.cmp(right.0));
    let mut out = Vec::new();
    out.extend_from_slice(SNAPSHOT_MAGIC);
    out.extend_from_slice(&SNAPSHOT_VERSION.to_be_bytes());
    out.extend_from_slice(&(group_bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(&group_bytes);
    out.extend_from_slice(&epoch.to_be_bytes());
    out.extend_from_slice(&(records.len() as u32).to_be_bytes());
    for (key, value) in records {
        if key.is_empty()
            || key.len() > MAX_RECORD_KEY_BYTES
            || value.len() > MAX_RECORD_VALUE_BYTES
        {
            return Err("snapshot_record_too_large");
        }
        out.extend_from_slice(&(key.len() as u32).to_be_bytes());
        out.extend_from_slice(&(value.len() as u32).to_be_bytes());
        out.extend_from_slice(key);
        out.extend_from_slice(value);
        if out.len() > MAX_SNAPSHOT_BYTES {
            return Err("snapshot_too_large");
        }
    }
    Ok(out)
}

fn provider_from_snapshot(bytes: &[u8]) -> Result<(SnapshotProvider, GroupId, u64), &'static str> {
    let decoded = decode_snapshot(bytes)?;
    let provider = SnapshotProvider::default();
    *provider
        .storage
        .values
        .write()
        .map_err(|_| "snapshot_storage_lock_failed")? = decoded.records;
    let group = MlsGroup::load(provider.storage(), &decoded.group_id)
        .map_err(|_| "group_reload_failed")?
        .ok_or("group_state_missing")?;
    if group.group_id() != &decoded.group_id || group.epoch().as_u64() != decoded.epoch {
        return Err("group_state_mismatch");
    }
    Ok((provider, decoded.group_id, decoded.epoch))
}

fn create_snapshot(previous: Option<&[u8]>) -> Result<Vec<u8>, &'static str> {
    if let Some(bytes) = previous {
        let (_provider, _group_id, _epoch) = provider_from_snapshot(bytes)?;
        // A restore call proves the serialized records still load as the same complete MLS group.
        // It returns the canonicalized bytes so the caller can compare/store them transactionally.
        return Ok(bytes.to_vec());
    }
    let provider = SnapshotProvider::default();
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
    let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "group_signature_key_generation_failed")?;
    signer
        .store(provider.storage())
        .map_err(|_| "group_signature_key_store_failed")?;
    let credential = BasicCredential::new(b"focaaa-durable-storage-spike".to_vec());
    let identity = CredentialWithKey {
        credential: credential.into(),
        signature_key: signer.public().into(),
    };
    let config = MlsGroupCreateConfig::builder()
        .ciphersuite(ciphersuite)
        .use_ratchet_tree_extension(true)
        .build();
    let group = MlsGroup::new(&provider, &signer, &config, identity)
        .map_err(|_| "group_creation_failed")?;
    let group_id = group.group_id().clone();
    let epoch = group.epoch().as_u64();
    encode_snapshot(&group_id, epoch, provider.storage())
}

fn snapshot_summary(bytes: &[u8]) -> Result<String, &'static str> {
    let (_provider, group_id, epoch) = provider_from_snapshot(bytes)?;
    let group_id_bytes = group_id
        .tls_serialize_detached()
        .map_err(|_| "snapshot_group_id_encode_failed")?;
    let group_id_hex: String = group_id_bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    Ok(format!(
        "openmls=0.9.2; snapshot=restored; group_id={group_id_hex}; epoch={epoch}"
    ))
}

const MAX_NATIVE_INPUT_BYTES: usize = 16 * 1024 * 1024;
const MAX_NATIVE_RESULT_BYTES: usize = 32 * 1024 * 1024;

fn pack_native_result(fields: &[&[u8]]) -> Result<Vec<u8>, &'static str> {
    let mut out = Vec::new();
    out.extend_from_slice(b"FOCAARES");
    out.extend_from_slice(&1_u32.to_be_bytes());
    out.extend_from_slice(&(fields.len() as u32).to_be_bytes());
    for field in fields {
        let length = u32::try_from(field.len()).map_err(|_| "native_result_field_too_large")?;
        out.extend_from_slice(&length.to_be_bytes());
        out.extend_from_slice(field);
        if out.len() > MAX_NATIVE_RESULT_BYTES {
            return Err("native_result_too_large");
        }
    }
    Ok(out)
}

fn load_snapshot_group(
    snapshot: &[u8],
) -> Result<(SnapshotProvider, GroupId, MlsGroup), &'static str> {
    let (provider, group_id, _) = provider_from_snapshot(snapshot)?;
    let group = MlsGroup::load(provider.storage(), &group_id)
        .map_err(|_| "group_reload_failed")?
        .ok_or("group_state_missing")?;
    Ok((provider, group_id, group))
}

fn create_identity_state(identity: &[u8]) -> Result<Vec<u8>, &'static str> {
    if identity.is_empty() || identity.len() > 256 {
        return Err("identity_credential_length_invalid");
    }
    let provider = SnapshotProvider::default();
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
    let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "identity_signer_generation_failed")?;
    signer
        .store(provider.storage())
        .map_err(|_| "identity_signer_store_failed")?;
    let credential = BasicCredential::new(identity.to_vec());
    let identity = CredentialWithKey {
        credential: credential.into(),
        signature_key: signer.public().into(),
    };
    let config = MlsGroupCreateConfig::builder()
        .ciphersuite(ciphersuite)
        .use_ratchet_tree_extension(true)
        .build();
    let group = MlsGroup::new(&provider, &signer, &config, identity)
        .map_err(|_| "identity_group_creation_failed")?;
    encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )
}

fn create_key_package(snapshot: &[u8]) -> Result<Vec<u8>, &'static str> {
    let (provider, _, group) = load_snapshot_group(snapshot)?;
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
    let leaf = group.own_leaf_node().ok_or("identity_leaf_missing")?;
    let signer = SignatureKeyPair::read(
        provider.storage(),
        leaf.signature_key().as_slice(),
        ciphersuite.signature_algorithm(),
    )
    .ok_or("identity_signer_missing")?;
    let credential = group
        .credential()
        .map_err(|_| "identity_credential_unavailable")?
        .clone();
    let key_package = KeyPackage::builder()
        .build(
            ciphersuite,
            &provider,
            &signer,
            CredentialWithKey {
                credential,
                signature_key: signer.public().into(),
            },
        )
        .map_err(|_| "key_package_creation_failed")?;
    let encoded = key_package
        .key_package()
        .tls_serialize_detached()
        .map_err(|_| "key_package_serialization_failed")?;
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &encoded])
}

fn add_member(snapshot: &[u8], encoded_key_package: &[u8]) -> Result<Vec<u8>, &'static str> {
    if encoded_key_package.is_empty() || encoded_key_package.len() > 64 * 1024 {
        return Err("key_package_length_invalid");
    }
    let (provider, _, mut group) = load_snapshot_group(snapshot)?;
    if group.members().count() != 1 {
        return Err("new_pairing_group_must_have_one_member");
    }
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
    let leaf = group.own_leaf_node().ok_or("identity_leaf_missing")?;
    let signer = SignatureKeyPair::read(
        provider.storage(),
        leaf.signature_key().as_slice(),
        ciphersuite.signature_algorithm(),
    )
    .ok_or("identity_signer_missing")?;
    let key_package_in = KeyPackageIn::tls_deserialize(&mut encoded_key_package.as_ref())
        .map_err(|_| "key_package_deserialization_failed")?;
    let key_package = key_package_in
        .validate(provider.crypto(), ProtocolVersion::Mls10)
        .map_err(|_| "key_package_validation_failed")?;
    let (commit, welcome, _) = group
        .add_members(&provider, &signer, &[key_package])
        .map_err(|_| "group_add_member_failed")?;
    let welcome = welcome
        .tls_serialize_detached()
        .map_err(|_| "welcome_serialization_failed")?;
    let commit = commit
        .tls_serialize_detached()
        .map_err(|_| "commit_serialization_failed")?;
    let ratchet_tree = group
        .export_ratchet_tree()
        .tls_serialize_detached()
        .map_err(|_| "ratchet_tree_serialization_failed")?;
    group
        .merge_pending_commit(&provider)
        .map_err(|_| "group_add_commit_merge_failed")?;
    let group_id = group
        .group_id()
        .tls_serialize_detached()
        .map_err(|_| "group_id_serialization_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &welcome, &ratchet_tree, &commit, &group_id, &epoch])
}

fn join_group(
    snapshot: &[u8],
    encoded_welcome: &[u8],
    encoded_ratchet_tree: &[u8],
) -> Result<Vec<u8>, &'static str> {
    if encoded_welcome.is_empty()
        || encoded_welcome.len() > 2 * 1024 * 1024
        || encoded_ratchet_tree.is_empty()
        || encoded_ratchet_tree.len() > 2 * 1024 * 1024
    {
        return Err("welcome_payload_length_invalid");
    }
    let (provider, _, _) = load_snapshot_group(snapshot)?;
    let welcome_message = MlsMessageIn::tls_deserialize(&mut encoded_welcome.as_ref())
        .map_err(|_| "welcome_deserialization_failed")?;
    let welcome = match welcome_message.extract() {
        MlsMessageBodyIn::Welcome(welcome) => welcome,
        _ => return Err("welcome_message_missing"),
    };
    let ratchet_tree = RatchetTreeIn::tls_deserialize(&mut encoded_ratchet_tree.as_ref())
        .map_err(|_| "ratchet_tree_deserialization_failed")?;
    let join_config = MlsGroupJoinConfig::builder()
        .use_ratchet_tree_extension(true)
        .build();
    let group =
        StagedWelcome::new_from_welcome(&provider, &join_config, welcome, Some(ratchet_tree))
            .and_then(|staged| staged.into_group(&provider))
            .map_err(|_| "welcome_processing_failed")?;
    let group_id = group
        .group_id()
        .tls_serialize_detached()
        .map_err(|_| "group_id_serialization_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &group_id, &epoch])
}

fn group_fingerprint_material(snapshot: &[u8]) -> Result<Vec<u8>, &'static str> {
    let (_, group_id, group) = load_snapshot_group(snapshot)?;
    let group_bytes = group_id
        .tls_serialize_detached()
        .map_err(|_| "group_id_serialization_failed")?;
    let mut credentials = Vec::new();
    for member in group.members() {
        credentials.push(
            member
                .credential
                .tls_serialize_detached()
                .map_err(|_| "member_credential_serialization_failed")?,
        );
    }
    if credentials.len() != 2 {
        return Err("pair_verification_requires_two_members");
    }
    credentials.sort();
    let own_credential = group
        .credential()
        .map_err(|_| "own_credential_unavailable")?
        .tls_serialize_detached()
        .map_err(|_| "own_credential_serialization_failed")?;
    pack_native_result(&[
        &group_bytes,
        &credentials[0],
        &credentials[1],
        &own_credential,
    ])
}

fn live_draft_key(snapshot: &[u8]) -> Result<Vec<u8>, &'static str> {
    let (provider, _, group) = load_snapshot_group(snapshot)?;
    if group.members().count() != 2 {
        return Err("live_draft_requires_two_members");
    }
    let key = group
        .export_secret(&provider.crypto, "focaaa-live-draft-v1", b"draft-frames", 32)
        .map_err(|_| "live_draft_export_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    pack_native_result(&[&key, &epoch])
}

fn encrypt_message(snapshot: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, &'static str> {
    if plaintext.is_empty() || plaintext.len() > 48 * 1024 {
        return Err("plaintext_length_invalid");
    }
    let (provider, _, mut group) = load_snapshot_group(snapshot)?;
    let leaf = group.own_leaf_node().ok_or("identity_leaf_missing")?;
    let signer = SignatureKeyPair::read(
        provider.storage(),
        leaf.signature_key().as_slice(),
        Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519.signature_algorithm(),
    )
    .ok_or("identity_signer_missing")?;
    let wire_message = group
        .create_message(&provider, &signer, plaintext)
        .map_err(|_| "application_message_encryption_failed")?;
    let wire = wire_message
        .tls_serialize_detached()
        .map_err(|_| "application_message_serialization_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &wire, &epoch])
}

fn rotate_group_state(snapshot: &[u8]) -> Result<Vec<u8>, &'static str> {
    let (provider, _, mut group) = load_snapshot_group(snapshot)?;
    let leaf = group.own_leaf_node().ok_or("identity_leaf_missing")?;
    let signer = SignatureKeyPair::read(
        provider.storage(),
        leaf.signature_key().as_slice(),
        Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519.signature_algorithm(),
    )
    .ok_or("identity_signer_missing")?;
    let commit = group
        .self_update(&provider, &signer, LeafNodeParameters::default())
        .map_err(|_| "group_rotation_failed")?
        .into_messages()
        .tls_serialize_detached()
        .map_err(|_| "group_rotation_serialization_failed")?;
    group
        .merge_pending_commit(&provider)
        .map_err(|_| "group_rotation_commit_merge_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &commit, &epoch])
}

fn remove_peer(snapshot: &[u8], peer_credential: &[u8]) -> Result<Vec<u8>, &'static str> {
    if peer_credential.is_empty() || peer_credential.len() > 64 * 1024 {
        return Err("peer_identity_length_invalid");
    }
    let (provider, _, mut group) = load_snapshot_group(snapshot)?;
    let own_credential = group
        .credential()
        .map_err(|_| "own_credential_unavailable")?
        .tls_serialize_detached()
        .map_err(|_| "own_credential_serialization_failed")?;
    if own_credential == peer_credential {
        return Err("cannot_remove_local_member");
    }
    let member = group
        .members()
        .find(|member| {
            member
                .credential
                .tls_serialize_detached()
                .map(|encoded| encoded == peer_credential)
                .unwrap_or(false)
        })
        .ok_or("peer_identity_not_in_group")?;
    let leaf = group.own_leaf_node().ok_or("identity_leaf_missing")?;
    let signer = SignatureKeyPair::read(
        provider.storage(),
        leaf.signature_key().as_slice(),
        Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519.signature_algorithm(),
    )
    .ok_or("identity_signer_missing")?;
    let (commit, _, _) = group
        .remove_members(&provider, &signer, &[member.index])
        .map_err(|_| "peer_removal_failed")?;
    let commit = commit
        .tls_serialize_detached()
        .map_err(|_| "peer_removal_serialization_failed")?;
    group
        .merge_pending_commit(&provider)
        .map_err(|_| "peer_removal_commit_merge_failed")?;
    let epoch = group.epoch().as_u64().to_be_bytes();
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    pack_native_result(&[&state, &commit, &epoch])
}

fn process_message(snapshot: &[u8], encoded_message: &[u8]) -> Result<Vec<u8>, &'static str> {
    if encoded_message.is_empty() || encoded_message.len() > 64 * 1024 {
        return Err("mls_message_length_invalid");
    }
    let (provider, _, mut group) = load_snapshot_group(snapshot)?;
    let message = MlsMessageIn::tls_deserialize(&mut encoded_message.as_ref())
        .map_err(|_| "mls_message_deserialization_failed")?;
    let message = message
        .try_into_protocol_message()
        .map_err(|_| "mls_protocol_message_invalid")?;
    let processed = group
        .process_message(&provider, message)
        .map_err(|_| "mls_message_authentication_failed")?;
    let message_epoch = processed.epoch().as_u64().to_be_bytes();
    let credential = processed
        .credential()
        .tls_serialize_detached()
        .map_err(|_| "sender_credential_serialization_failed")?;
    let outcome = match processed.into_content() {
        ProcessedMessageContent::ApplicationMessage(message) => {
            pack_native_result(&[&[1], &message.into_bytes(), &credential, &message_epoch])?
        }
        ProcessedMessageContent::ProposalMessage(proposal) => {
            group
                .store_pending_proposal(provider.storage(), *proposal)
                .map_err(|_| "proposal_persistence_failed")?;
            pack_native_result(&[&[2], &[], &credential, &message_epoch])?
        }
        ProcessedMessageContent::ExternalJoinProposalMessage(_) => {
            return Err("external_join_proposal_not_supported");
        }
        ProcessedMessageContent::StagedCommitMessage(commit) => {
            group
                .merge_staged_commit(&provider, *commit)
                .map_err(|_| "mls_commit_merge_failed")?;
            pack_native_result(&[&[2], &[], &credential, &message_epoch])?
        }
        ProcessedMessageContent::OwnPendingCommit => {
            group
                .merge_pending_commit(&provider)
                .map_err(|_| "mls_pending_commit_merge_failed")?;
            pack_native_result(&[&[3], &[], &credential, &message_epoch])?
        }
        ProcessedMessageContent::OwnPrivateMessage => {
            pack_native_result(&[&[3], &[], &credential, &message_epoch])?
        }
    };
    let state = encode_snapshot(
        &group.group_id().clone(),
        group.epoch().as_u64(),
        provider.storage(),
    )?;
    // The outer result contains the new state and an outcome tuple. The receiver decides whether
    // to atomically commit a message row or a state-only inbox record before acknowledging.
    pack_native_result(&[&state, &outcome])
}

fn native_read_bytes(env: &mut JNIEnv, input: &JByteArray) -> Result<Vec<u8>, &'static str> {
    let length = env
        .get_array_length(input)
        .map_err(|_| "native_input_unreadable")? as usize;
    if length > MAX_NATIVE_INPUT_BYTES {
        return Err("native_input_too_large");
    }
    env.convert_byte_array(input)
        .map_err(|_| "native_input_unreadable")
}

fn native_throw(env: &mut JNIEnv, code: &'static str) -> jbyteArray {
    let _ = env.throw_new("java/lang/IllegalStateException", code);
    std::ptr::null_mut()
}

fn native_return_bytes(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_createIdentityNative(
    mut env: JNIEnv,
    _this: JObject,
    identity: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let identity = native_read_bytes(&mut env, &identity)?;
        let snapshot = create_identity_state(&identity)?;
        pack_native_result(&[&snapshot])
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_identity_creation_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_createKeyPackageNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        create_key_package(&snapshot)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_key_package_creation_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_addMemberNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
    key_package: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        let key_package = native_read_bytes(&mut env, &key_package)?;
        add_member(&snapshot, &key_package)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_group_member_addition_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_joinGroupNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
    welcome: JByteArray,
    ratchet_tree: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        let welcome = native_read_bytes(&mut env, &welcome)?;
        let ratchet_tree = native_read_bytes(&mut env, &ratchet_tree)?;
        join_group(&snapshot, &welcome, &ratchet_tree)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_group_join_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_fingerprintNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        group_fingerprint_material(&snapshot)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_fingerprint_generation_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_liveDraftKeyNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        live_draft_key(&snapshot)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_live_draft_export_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_encryptNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        let plaintext = native_read_bytes(&mut env, &plaintext)?;
        encrypt_message(&snapshot, &plaintext)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_message_encryption_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_rotateGroupStateNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        rotate_group_state(&snapshot)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_group_rotation_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_removePeerNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
    peer_credential: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        let peer_credential = native_read_bytes(&mut env, &peer_credential)?;
        remove_peer(&snapshot, &peer_credential)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_peer_removal_panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_NativeOpenMls_processMessageNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
    message: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let snapshot = native_read_bytes(&mut env, &snapshot)?;
        let message = native_read_bytes(&mut env, &message)?;
        process_message(&snapshot, &message)
    }));
    match result {
        Ok(Ok(bytes)) => native_return_bytes(&mut env, &bytes),
        Ok(Err(code)) => native_throw(&mut env, code),
        Err(_) => native_throw(&mut env, "native_message_processing_panicked"),
    }
}

fn run_compatibility_probe() -> Result<String, &'static str> {
    let provider = OpenMlsRustCrypto::default();
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

    let key_package_signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "signature_key_generation_failed")?;
    let key_package_credential =
        BasicCredential::new(b"focaaa-native-compatibility-probe".to_vec());
    let key_package_identity = CredentialWithKey {
        credential: key_package_credential.into(),
        signature_key: key_package_signer.public().into(),
    };
    let key_package_bundle = KeyPackage::builder()
        .build(
            ciphersuite,
            &provider,
            &key_package_signer,
            key_package_identity,
        )
        .map_err(|_| "key_package_creation_failed")?;

    let encoded_key_package = key_package_bundle
        .key_package()
        .tls_serialize_detached()
        .map_err(|_| "key_package_serialization_failed")?;
    let key_package_in = KeyPackageIn::tls_deserialize(&mut encoded_key_package.as_slice())
        .map_err(|_| "key_package_deserialization_failed")?;
    key_package_in
        .validate(provider.crypto(), ProtocolVersion::Mls10)
        .map_err(|_| "key_package_validation_failed")?;

    let group_signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "group_signature_key_generation_failed")?;
    let group_credential = BasicCredential::new(b"focaaa-group-storage-probe".to_vec());
    let group_identity = CredentialWithKey {
        credential: group_credential.into(),
        signature_key: group_signer.public().into(),
    };
    let create_config = MlsGroupCreateConfig::builder()
        .ciphersuite(ciphersuite)
        .use_ratchet_tree_extension(true)
        .build();
    let group = MlsGroup::new(&provider, &group_signer, &create_config, group_identity)
        .map_err(|_| "group_creation_failed")?;
    let expected_group_id = group.group_id().clone();
    let expected_epoch = group.epoch();
    drop(group);

    // This confirms OpenMLS can reconstruct serialized entities through its provider storage.
    // The provider is memory-backed here, so this is not a process-restart or Android persistence test.
    let restored = MlsGroup::load(provider.storage(), &expected_group_id)
        .map_err(|_| "group_reload_failed")?
        .ok_or("group_state_missing")?;
    if restored.group_id() != &expected_group_id || restored.epoch() != expected_epoch {
        return Err("group_state_mismatch");
    }

    Ok("openmls=0.9.2; key_package_roundtrip=ok; group_reload=ok; storage=memory_only".to_owned())
}

/// Exercises a real two-member MLS join and application-message round trip across serialized
/// provider maps. This is still an in-memory debug probe; the Android encrypted-store gate is
/// separately verified by OpenMlsSnapshotStoreProbe and neither probe is production messaging.
pub fn run_two_member_snapshot_probe() -> Result<String, &'static str> {
    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
    let inviter_provider = SnapshotProvider::default();
    let invitee_provider = SnapshotProvider::default();

    let inviter_signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "inviter_signer_generation_failed")?;
    inviter_signer
        .store(inviter_provider.storage())
        .map_err(|_| "inviter_signer_store_failed")?;
    let inviter_credential = BasicCredential::new(b"focaaa-probe-inviter".to_vec());
    let inviter_identity = CredentialWithKey {
        credential: inviter_credential.into(),
        signature_key: inviter_signer.public().into(),
    };
    let group_config = MlsGroupCreateConfig::builder()
        .ciphersuite(ciphersuite)
        .use_ratchet_tree_extension(true)
        .build();
    let mut inviter_group = MlsGroup::new(
        &inviter_provider,
        &inviter_signer,
        &group_config,
        inviter_identity,
    )
    .map_err(|_| "inviter_group_creation_failed")?;

    let invitee_signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .map_err(|_| "invitee_signer_generation_failed")?;
    invitee_signer
        .store(invitee_provider.storage())
        .map_err(|_| "invitee_signer_store_failed")?;
    let invitee_credential = BasicCredential::new(b"focaaa-probe-invitee".to_vec());
    let invitee_identity = CredentialWithKey {
        credential: invitee_credential.into(),
        signature_key: invitee_signer.public().into(),
    };
    let key_package = KeyPackage::builder()
        .build(
            ciphersuite,
            &invitee_provider,
            &invitee_signer,
            invitee_identity,
        )
        .map_err(|_| "invitee_key_package_creation_failed")?
        .key_package()
        .to_owned();
    let (_commit, welcome_message, _group_info) = inviter_group
        .add_members(&inviter_provider, &inviter_signer, &[key_package])
        .map_err(|_| "member_add_failed")?;
    let welcome_bytes = welcome_message
        .tls_serialize_detached()
        .map_err(|_| "welcome_serialization_failed")?;
    let welcome = MlsMessageIn::tls_deserialize(&mut welcome_bytes.as_slice())
        .map_err(|_| "welcome_deserialization_failed")?;
    let welcome = match welcome.extract() {
        MlsMessageBodyIn::Welcome(welcome) => welcome,
        _ => return Err("welcome_message_missing"),
    };
    inviter_group
        .merge_pending_commit(&inviter_provider)
        .map_err(|_| "inviter_commit_merge_failed")?;

    let join_config = MlsGroupJoinConfig::builder()
        .use_ratchet_tree_extension(true)
        .build();
    let invitee_group = StagedWelcome::new_from_welcome(
        &invitee_provider,
        &join_config,
        welcome,
        Some(inviter_group.export_ratchet_tree().into()),
    )
    .and_then(|staged| staged.into_group(&invitee_provider))
    .map_err(|_| "invitee_welcome_processing_failed")?;

    let inviter_id = inviter_group.group_id().clone();
    let invitee_id = invitee_group.group_id().clone();
    if inviter_id != invitee_id || inviter_group.epoch() != invitee_group.epoch() {
        return Err("joined_group_state_mismatch");
    }
    let inviter_epoch = inviter_group.epoch().as_u64();
    let invitee_epoch = invitee_group.epoch().as_u64();
    let inviter_snapshot = encode_snapshot(&inviter_id, inviter_epoch, inviter_provider.storage())?;
    let invitee_snapshot = encode_snapshot(&invitee_id, invitee_epoch, invitee_provider.storage())?;
    let (restored_inviter_provider, _, _) = provider_from_snapshot(&inviter_snapshot)?;
    let (restored_invitee_provider, _, _) = provider_from_snapshot(&invitee_snapshot)?;
    let inviter_draft = live_draft_key(&inviter_snapshot)?;
    let invitee_draft = live_draft_key(&invitee_snapshot)?;
    if inviter_draft != invitee_draft {
        return Err("live_draft_export_mismatch");
    }
    let mut restored_inviter = MlsGroup::load(restored_inviter_provider.storage(), &inviter_id)
        .map_err(|_| "inviter_group_reload_failed")?
        .ok_or("inviter_group_state_missing")?;
    let mut restored_invitee = MlsGroup::load(restored_invitee_provider.storage(), &invitee_id)
        .map_err(|_| "invitee_group_reload_failed")?
        .ok_or("invitee_group_state_missing")?;
    let restored_inviter_signer = SignatureKeyPair::read(
        restored_inviter_provider.storage(),
        inviter_signer.public(),
        ciphersuite.signature_algorithm(),
    )
    .ok_or("inviter_signer_reload_failed")?;
    let restored_invitee_signer = SignatureKeyPair::read(
        restored_invitee_provider.storage(),
        invitee_signer.public(),
        ciphersuite.signature_algorithm(),
    )
    .ok_or("invitee_signer_reload_failed")?;

    let inviter_message = b"focaaa-probe-inviter-to-invitee";
    let encrypted_from_inviter = restored_inviter
        .create_message(
            &restored_inviter_provider,
            &restored_inviter_signer,
            inviter_message,
        )
        .map_err(|_| "inviter_message_creation_failed")?;
    let inviter_message_bytes = encrypted_from_inviter
        .tls_serialize_detached()
        .map_err(|_| "inviter_message_serialization_failed")?;
    let inviter_message_in = MlsMessageIn::tls_deserialize(&mut inviter_message_bytes.as_slice())
        .map_err(|_| "inviter_message_decode_failed")?;
    let inviter_protocol_message = inviter_message_in
        .try_into_protocol_message()
        .map_err(|_| "inviter_message_not_protocol_message")?;
    let received_by_invitee = restored_invitee
        .process_message(&restored_invitee_provider, inviter_protocol_message)
        .map_err(|_| "invitee_message_processing_failed")?;
    match received_by_invitee.into_content() {
        ProcessedMessageContent::ApplicationMessage(message) => {
            if message.into_bytes() != inviter_message {
                return Err("invitee_plaintext_mismatch");
            }
        }
        _ => return Err("invitee_plaintext_mismatch"),
    }

    let invitee_message = b"focaaa-probe-invitee-to-inviter";
    let encrypted_from_invitee = restored_invitee
        .create_message(
            &restored_invitee_provider,
            &restored_invitee_signer,
            invitee_message,
        )
        .map_err(|_| "invitee_message_creation_failed")?;
    let invitee_message_bytes = encrypted_from_invitee
        .tls_serialize_detached()
        .map_err(|_| "invitee_message_serialization_failed")?;
    let invitee_message_in = MlsMessageIn::tls_deserialize(&mut invitee_message_bytes.as_slice())
        .map_err(|_| "invitee_message_decode_failed")?;
    let invitee_protocol_message = invitee_message_in
        .try_into_protocol_message()
        .map_err(|_| "invitee_message_not_protocol_message")?;
    let received_by_inviter = restored_inviter
        .process_message(&restored_inviter_provider, invitee_protocol_message)
        .map_err(|_| "inviter_message_processing_failed")?;
    match received_by_inviter.into_content() {
        ProcessedMessageContent::ApplicationMessage(message) => {
            if message.into_bytes() != invitee_message {
                return Err("inviter_plaintext_mismatch");
            }
        }
        _ => return Err("inviter_plaintext_mismatch"),
    }

    Ok("openmls=0.9.2; two_member_welcome=ok; snapshot_restore=ok; signing_key_restore=ok; inviter_to_invitee=ok; invitee_to_inviter=ok; draft_export=ok; storage=memory_only".to_owned())
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_OpenMlsCompatibilityProbe_runNative(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(run_compatibility_probe)) {
        Ok(Ok(result)) => match env.new_string(result) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(code)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", code);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "openmls_compatibility_probe_panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_OpenMlsCompatibilityProbe_runTwoMemberSnapshotProbe(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(run_two_member_snapshot_probe)) {
        Ok(Ok(result)) => match env.new_string(result) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(code)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", code);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "two_member_snapshot_probe_panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_OpenMlsCompatibilityProbe_createSnapshotNative(
    mut env: JNIEnv,
    _this: JObject,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| create_snapshot(None))) {
        Ok(Ok(snapshot)) => match env.byte_array_from_slice(&snapshot) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(code)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", code);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "snapshot_creation_panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_focaaa_chat_crypto_OpenMlsCompatibilityProbe_restoreSnapshotNative(
    mut env: JNIEnv,
    _this: JObject,
    snapshot: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let input_len = env
            .get_array_length(&snapshot)
            .map_err(|_| "snapshot_input_unreadable")? as usize;
        if input_len > MAX_SNAPSHOT_BYTES {
            return Err("snapshot_too_large");
        }
        let bytes = env
            .convert_byte_array(snapshot)
            .map_err(|_| "snapshot_input_unreadable")?;
        snapshot_summary(&bytes)
    }));
    match result {
        Ok(Ok(summary)) => match env.new_string(summary) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(code)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", code);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "snapshot_restore_panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod snapshot_format_tests {
    use super::*;

    #[test]
    fn two_members_export_the_same_live_draft_key() {
        let result = run_two_member_snapshot_probe().unwrap();
        assert!(result.contains("draft_export=ok"), "{result}");
    }

    #[test]
    fn snapshot_restores_the_same_group() {
        let bytes = create_snapshot(None).unwrap();
        let summary = snapshot_summary(&bytes).unwrap();
        assert!(summary.contains("snapshot=restored"));
        assert_eq!(create_snapshot(Some(&bytes)).unwrap(), bytes);
    }

    #[test]
    fn rejects_malformed_snapshot_boundaries_and_versions() {
        let bytes = create_snapshot(None).unwrap();
        assert_eq!(decode_snapshot(&bytes[..7]).err(), Some("snapshot_bad_magic"));
        let mut version = bytes.clone();
        version[11] = 2;
        assert_eq!(decode_snapshot(&version).err(), Some("snapshot_unsupported_version"));
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert_eq!(decode_snapshot(&trailing).err(), Some("snapshot_trailing_bytes"));
        assert_eq!(decode_snapshot(&bytes[..bytes.len() - 1]).err(), Some("snapshot_truncated"));
        assert_eq!(decode_snapshot(&vec![0; MAX_SNAPSHOT_BYTES + 1]).err(), Some("snapshot_too_large"));
    }

    #[test]
    fn rejects_a_duplicate_storage_record() {
        let mut bytes = create_snapshot(None).unwrap();
        let group_len = u32::from_be_bytes(bytes[12..16].try_into().unwrap()) as usize;
        let count_at = 16 + group_len + 8;
        let count = u32::from_be_bytes(bytes[count_at..count_at + 4].try_into().unwrap());
        let first_at = count_at + 4;
        let key_len = u32::from_be_bytes(bytes[first_at..first_at + 4].try_into().unwrap()) as usize;
        let value_len = u32::from_be_bytes(bytes[first_at + 4..first_at + 8].try_into().unwrap()) as usize;
        let first_record = bytes[first_at..first_at + 8 + key_len + value_len].to_vec();
        bytes[count_at..count_at + 4].copy_from_slice(&(count + 1).to_be_bytes());
        bytes.extend_from_slice(&first_record);
        assert_eq!(decode_snapshot(&bytes).err(), Some("snapshot_duplicate_record"));
    }
}
