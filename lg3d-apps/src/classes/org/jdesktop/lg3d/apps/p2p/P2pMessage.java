/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.p2p;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The CONTROL-plane message that rides inside a {@link SecureFrame.Type#CONTROL}
 * frame, serialised as JSON by Jackson. One flat, forward-compatible bean carries
 * every kind of signal the two apps need over P2P - chat, presence, typing, the
 * whole file-transfer control plane, video-conference invites, keep-alive and
 * goodbye - discriminated by a {@link Kind}. A flat bean (rather than a
 * polymorphic type hierarchy) keeps the wire format stable and avoids Jackson
 * polymorphic-type deserialisation, which is both heavier and a known gadget
 * risk; unknown fields are ignored so a newer peer can add fields an older one
 * still reads.
 *
 * <p>Messages are built through the static factories ({@link #chat}, {@link
 * #fileOffer}, {@link #invite}, ...) which set the discriminant and the relevant
 * fields, then sealed into a frame by {@link SecureFrame#control(P2pMessage)}.
 * Only the fields meaningful to a kind are populated; {@code @JsonInclude(NON_NULL)}
 * keeps the JSON compact. No field here is secret - the whole frame is encrypted
 * and authenticated by the transport - so nothing needs {@code @JsonIgnore}.</p>
 *
 * <p>This type is AWT-free and headless-testable; the panels map it onto the
 * Messenger's {@code ChatMessage}/{@code ProtocolListener} model.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class P2pMessage {

    /** The shared mapper: omits nulls, tolerates unknown fields from newer peers. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** What a CONTROL message signals. */
    public enum Kind {
        /** A normal chat message. */
        CHAT,
        /** A {@code /me}-style action. */
        ACTION,
        /** Presence / status broadcast (carries the sender's fingerprint). */
        PRESENCE,
        /** A typing notification. */
        TYPING,
        /** An offer to send a file. */
        FILE_OFFER,
        /** The receiver accepted a file offer. */
        FILE_ACCEPT,
        /** The receiver rejected a file offer. */
        FILE_REJECT,
        /** Incremental transfer progress. */
        FILE_PROGRESS,
        /** The transfer finished; carries the verified SHA-256. */
        FILE_END,
        /** The transfer was cancelled. */
        FILE_CANCEL,
        /** A video-conference invite (room + share URL). */
        INVITE,
        /** Keep-alive request. */
        PING,
        /** Keep-alive response. */
        PONG,
        /** The peer is going away. */
        BYE
    }

    private Kind kind = Kind.CHAT;
    private String from;
    private String text;
    private String fingerprint;
    private String transferId;
    private String fileName;
    private String fileHash;
    private Long fileSize;
    private Long bytesDone;
    private String room;
    private String url;
    private Boolean typing;
    private long epochMs = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public P2pMessage() {
    }

    private P2pMessage(Kind kind) {
        this.kind = (kind == null) ? Kind.CHAT : kind;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** A chat message. */
    public static P2pMessage chat(String from, String text) {
        P2pMessage m = new P2pMessage(Kind.CHAT);
        m.from = from;
        m.text = text;
        return m;
    }

    /** A {@code /me} action. */
    public static P2pMessage action(String from, String text) {
        P2pMessage m = new P2pMessage(Kind.ACTION);
        m.from = from;
        m.text = text;
        return m;
    }

    /** A presence/status broadcast carrying the sender's identity fingerprint. */
    public static P2pMessage presence(String from, String status, String fingerprint) {
        P2pMessage m = new P2pMessage(Kind.PRESENCE);
        m.from = from;
        m.text = status;
        m.fingerprint = fingerprint;
        return m;
    }

    /** A typing notification. */
    public static P2pMessage typing(String from, boolean typing) {
        P2pMessage m = new P2pMessage(Kind.TYPING);
        m.from = from;
        m.typing = typing;
        return m;
    }

    /** An offer to send a file, with its size and SHA-256 for later verification. */
    public static P2pMessage fileOffer(String from, String transferId, String fileName,
                                       long fileSize, String fileHash) {
        P2pMessage m = new P2pMessage(Kind.FILE_OFFER);
        m.from = from;
        m.transferId = transferId;
        m.fileName = fileName;
        m.fileSize = fileSize;
        m.fileHash = fileHash;
        return m;
    }

    /** Acceptance of a file offer. */
    public static P2pMessage fileAccept(String from, String transferId) {
        P2pMessage m = new P2pMessage(Kind.FILE_ACCEPT);
        m.from = from;
        m.transferId = transferId;
        return m;
    }

    /** Rejection of a file offer, with a reason. */
    public static P2pMessage fileReject(String from, String transferId, String reason) {
        P2pMessage m = new P2pMessage(Kind.FILE_REJECT);
        m.from = from;
        m.transferId = transferId;
        m.text = reason;
        return m;
    }

    /** Incremental progress for a transfer. */
    public static P2pMessage fileProgress(String from, String transferId, long bytesDone) {
        P2pMessage m = new P2pMessage(Kind.FILE_PROGRESS);
        m.from = from;
        m.transferId = transferId;
        m.bytesDone = bytesDone;
        return m;
    }

    /** Completion of a transfer, carrying the verified SHA-256. */
    public static P2pMessage fileEnd(String from, String transferId, String fileHash) {
        P2pMessage m = new P2pMessage(Kind.FILE_END);
        m.from = from;
        m.transferId = transferId;
        m.fileHash = fileHash;
        return m;
    }

    /** Cancellation of a transfer, with a reason. */
    public static P2pMessage fileCancel(String from, String transferId, String reason) {
        P2pMessage m = new P2pMessage(Kind.FILE_CANCEL);
        m.from = from;
        m.transferId = transferId;
        m.text = reason;
        return m;
    }

    /** A video-conference invite: the room name and the ready-to-open share URL. */
    public static P2pMessage invite(String from, String room, String url) {
        P2pMessage m = new P2pMessage(Kind.INVITE);
        m.from = from;
        m.room = room;
        m.url = url;
        return m;
    }

    /** A keep-alive request. */
    public static P2pMessage ping() {
        return new P2pMessage(Kind.PING);
    }

    /** A keep-alive response. */
    public static P2pMessage pong() {
        return new P2pMessage(Kind.PONG);
    }

    /** A goodbye, with an optional reason. */
    public static P2pMessage bye(String reason) {
        P2pMessage m = new P2pMessage(Kind.BYE);
        m.text = reason;
        return m;
    }

    // ------------------------------------------------------------------
    // JSON codec
    // ------------------------------------------------------------------

    /**
     * Serialises this message to UTF-8 JSON.
     *
     * @return the JSON bytes, never null (an empty object at worst)
     */
    public byte[] toJsonBytes() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // A flat bean of strings/numbers cannot fail to serialise; be safe.
            return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses a message from JSON bytes, tolerating malformed input.
     *
     * @param json the JSON bytes (may be null)
     * @return the message, or null if the bytes are missing or malformed
     */
    public static P2pMessage fromJsonBytes(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(json, P2pMessage.class);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = (kind == null) ? Kind.CHAT : kind; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    /** The offered/total file size in bytes, or 0 when unset. */
    public long getFileSize() { return (fileSize == null) ? 0L : fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    /** The number of bytes transferred so far, or 0 when unset. */
    public long getBytesDone() { return (bytesDone == null) ? 0L : bytesDone; }
    public void setBytesDone(Long bytesDone) { this.bytesDone = bytesDone; }

    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    /** True for a positive typing notification. */
    public boolean isTyping() { return Boolean.TRUE.equals(typing); }
    public void setTyping(Boolean typing) { this.typing = typing; }

    public long getEpochMs() { return epochMs; }
    public void setEpochMs(long epochMs) { this.epochMs = epochMs; }

    @Override
    public String toString() {
        return "P2pMessage[" + kind + (from == null ? "" : " from=" + from)
                + (transferId == null ? "" : " transfer=" + transferId) + "]";
    }
}
