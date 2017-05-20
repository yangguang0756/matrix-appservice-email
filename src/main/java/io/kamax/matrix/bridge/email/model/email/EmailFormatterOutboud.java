/*
 * matrix-appservice-email - Matrix Bridge to E-mail
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.matrix.bridge.email.model.email;

import io.kamax.matrix.MatrixID;
import io.kamax.matrix._MatrixUser;
import io.kamax.matrix.bridge.email.config.ServerConfig;
import io.kamax.matrix.bridge.email.config.email.EmailSenderConfig;
import io.kamax.matrix.bridge.email.model.BridgeMessageHtmlContent;
import io.kamax.matrix.bridge.email.model.BridgeMessageTextContent;
import io.kamax.matrix.bridge.email.model._BridgeMessageContent;
import io.kamax.matrix.bridge.email.model.matrix._MatrixBridgeMessage;
import io.kamax.matrix.bridge.email.model.subscription.SubscriptionEvents;
import io.kamax.matrix.bridge.email.model.subscription._BridgeSubscription;
import io.kamax.matrix.bridge.email.model.subscription._SubscriptionEvent;
import io.kamax.matrix.client._MatrixClient;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class EmailFormatterOutboud implements InitializingBean, _EmailFormatterOutbound {

    private Logger log = LoggerFactory.getLogger(EmailFormatterOutboud.class);

    @Autowired
    private ServerConfig srvCfg;

    @Autowired
    private EmailSenderConfig sendCfg;

    @Autowired
    private _EmailTemplateManager templateMgr;

    private Session session = Session.getInstance(System.getProperties());

    private DateTimeFormatter hourFormatter = DateTimeFormatter.ofPattern("HH");
    private DateTimeFormatter minFormatter = DateTimeFormatter.ofPattern("mm");
    private DateTimeFormatter secFormatter = DateTimeFormatter.ofPattern("ss");

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!templateMgr.get(SubscriptionEvents.OnMessage).isPresent()) {
            log.error("Configuration error: template list for onMessage notification event cannot be empty");
            System.exit(1);
        }
    }

    private String getSubscriptionManageLink(String token) {
        return srvCfg.getHost() + "/subscription?token=" + token;
    }

    private String getHtml(String text) {
        return "<span>" + text + "</span>";
    }

    private String processToken(TokenData data, String template) {
        template = StringUtils.replace(template, EmailTemplateToken.ManageUrl.getToken(), data.getManageUrl());
        template = StringUtils.replace(template, EmailTemplateToken.MsgTimeHour.getToken(), data.getTimeHour());
        template = StringUtils.replace(template, EmailTemplateToken.MsgTimeMin.getToken(), data.getTimeMin());
        template = StringUtils.replace(template, EmailTemplateToken.MsgTimeSec.getToken(), data.getTimeSec());
        template = StringUtils.replace(template, EmailTemplateToken.ReceiverAddress.getToken(), data.getReceiverAddress());
        template = StringUtils.replace(template, EmailTemplateToken.SenderAddress.getToken(), data.getSenderAddress());
        template = StringUtils.replace(template, EmailTemplateToken.SenderName.getToken(), data.getSenderName());
        template = StringUtils.replace(template, EmailTemplateToken.SenderAvatar.getToken(), data.getSenderAvatar());
        template = StringUtils.replace(template, EmailTemplateToken.RoomAddress.getToken(), data.getRoomAddress());
        template = StringUtils.replace(template, EmailTemplateToken.RoomName.getToken(), data.getRoomName());
        template = StringUtils.replace(template, EmailTemplateToken.Room.getToken(), data.getRoom());

        return template;
    }

    private String processToken(TokenData data, String template, String content) {
        return StringUtils.replace(processToken(data, template), EmailTemplateToken.MsgContent.getToken(), content);
    }

    private MimeBodyPart makeBodyPart(TokenData data, _EmailTemplateContent template, _BridgeMessageContent content) throws IOException, MessagingException {
        StringBuilder partRaw = new StringBuilder();

        String header = processToken(data, template.getHeader());
        String footer = processToken(data, template.getFooter());
        String contentString = processToken(data, template.getContent(), content.getContentAsString());

        partRaw.append(header).append(contentString).append(footer);

        MimeBodyPart part = new MimeBodyPart();
        part.setText(partRaw.toString(), StandardCharsets.UTF_8.name(), template.getType().replace("text/", ""));

        log.info("Created body part of type {}", template.getType());

        return part;
    }

    private MimeMessage makeEmail(TokenData data, _EmailTemplate template, MimeMultipart body, boolean allowReply) throws MessagingException, UnsupportedEncodingException {
        MimeMessage msg = new MimeMessage(session);
        if (allowReply) {
            msg.setReplyTo(InternetAddress.parse(sendCfg.getTemplate().replace("%KEY%", data.getKey())));
        }

        msg.setFrom(new InternetAddress(sendCfg.getEmail(), data.getSenderName(), StandardCharsets.UTF_8.name()));
        msg.setSubject(processToken(data, template.getSubject()));
        msg.setContent(body);
        return msg;
    }

    private MimeMessage makeEmail(TokenData data, _EmailTemplate template, List<_BridgeMessageContent> contents, boolean allowReply) throws MessagingException, IOException {
        MimeMultipart body = new MimeMultipart();
        body.setSubType("alternative");

        for (_BridgeMessageContent content : contents) {
            Optional<_EmailTemplateContent> contentTemplateOpt = template.getContent(content.getMime());
            if (!contentTemplateOpt.isPresent()) {
                continue;
            }

            body.addBodyPart(makeBodyPart(data, contentTemplateOpt.get(), content));
        }

        return makeEmail(data, template, body, allowReply);
    }

    private MimeMessage makeEmail(TokenData data, _EmailTemplate template, boolean allowReply) throws IOException, MessagingException {
        List<_BridgeMessageContent> contents = Arrays.asList(
                new BridgeMessageTextContent(MimeTypeUtils.TEXT_PLAIN_VALUE),
                new BridgeMessageTextContent(MimeTypeUtils.TEXT_HTML_VALUE)
        );

        return makeEmail(data, template, contents, allowReply);
    }

    @Override
    public Optional<MimeMessage> get(_BridgeSubscription sub, _MatrixBridgeMessage msg) throws IOException, MessagingException {
        Optional<_EmailTemplate> templateOpt = templateMgr.get(SubscriptionEvents.OnMessage);
        if (!templateOpt.isPresent()) {
            log.info("Ignoring message event {} to {}, no notification set", msg.getKey(), sub.getEmailEndpoint().getIdentity());
            return Optional.empty();
        }

        _EmailTemplate template = templateOpt.get();
        List<_EmailTemplateContent> templates = template.listContents();
        if (templates.isEmpty()) {
            log.info("No template configured for subscription event {}, skipping");
            return Optional.empty();
        }

        Optional<_BridgeMessageContent> txtOpt = msg.getContent(MimeTypeUtils.TEXT_PLAIN_VALUE);
        Optional<_BridgeMessageContent> htmlOpt = msg.getContent(MimeTypeUtils.TEXT_HTML_VALUE);

        List<_BridgeMessageContent> contents = new ArrayList<>();
        if (!txtOpt.isPresent()) {
            if (!htmlOpt.isPresent()) {
                log.warn("Ignoring Matrix message {} to {}, no valid content", msg.getKey(), sub.getEmailEndpoint().getIdentity());
                return Optional.empty();
            }

            contents.add(htmlOpt.get());
        } else {
            contents.add(txtOpt.get());
            if (htmlOpt.isPresent()) {
                contents.add(htmlOpt.get());
            } else {
                contents.add(new BridgeMessageHtmlContent(getHtml(txtOpt.get().getContentAsString())));
            }
        }

        _MatrixClient mxClient = sub.getMatrixEndpoint().getClient();
        _MatrixUser userSource = msg.getSender();
        LocalDateTime ldt = LocalDateTime.ofInstant(msg.getTime(), ZoneOffset.systemDefault());
        TokenData tokenData = new TokenData(sub.getEmailEndpoint().getChannelId());
        tokenData.setManageUrl(getSubscriptionManageLink(sub.getEmailEndpoint().getChannelId()));
        tokenData.setTimeHour(ldt.format(hourFormatter));
        tokenData.setTimeMin(ldt.format(minFormatter));
        tokenData.setTimeSec(ldt.format(secFormatter));
        tokenData.setSenderAddress(userSource.getId().getId());
        tokenData.setSenderName(userSource.getName().orElse(userSource.getId().getId()));
        tokenData.setReceiverAddress(sub.getEmailEndpoint().getIdentity());
        tokenData.setRoomAddress(sub.getMatrixEndpoint().getChannelId());
        tokenData.setRoomName(mxClient.getRoom(tokenData.getRoomAddress()).getName().orElse(""));
        tokenData.setRoom(StringUtils.defaultIfBlank(tokenData.getRoomName(), tokenData.getRoomAddress()));

        return Optional.of(makeEmail(tokenData, template, contents, true));
    }

    @Override
    public Optional<MimeMessage> get(_SubscriptionEvent ev) throws IOException, MessagingException {
        Optional<_EmailTemplate> templateOpt = templateMgr.get(ev.getType());
        if (!templateOpt.isPresent()) {
            log.info("Ignoring subscription event {} to {}, no notification set", ev.getType(), ev.getSubscription().getEmailEndpoint().getIdentity());
            return Optional.empty();
        }

        _EmailTemplate template = templateOpt.get();
        List<_EmailTemplateContent> templates = template.listContents();
        if (templates.isEmpty()) {
            log.info("No template configured for subscription event {}, skipping");
            return Optional.empty();
        }

        _MatrixClient mxClient = ev.getSubscription().getMatrixEndpoint().getClient();
        _MatrixUser userSource = mxClient.getUser(new MatrixID(ev.getInitiator()));
        LocalDateTime ldt = LocalDateTime.ofInstant(ev.getTime(), ZoneOffset.systemDefault());
        TokenData tokenData = new TokenData(ev.getSubscription().getEmailEndpoint().getChannelId());
        tokenData.setManageUrl(getSubscriptionManageLink(ev.getSubscription().getEmailEndpoint().getChannelId()));
        tokenData.setTimeHour(ldt.format(hourFormatter));
        tokenData.setTimeMin(ldt.format(minFormatter));
        tokenData.setTimeSec(ldt.format(secFormatter));
        tokenData.setSenderAddress(userSource.getId().getId());
        tokenData.setSenderName(userSource.getName().orElse(userSource.getId().getId()));
        tokenData.setReceiverAddress(ev.getSubscription().getEmailEndpoint().getIdentity());
        tokenData.setRoomAddress(ev.getSubscription().getMatrixEndpoint().getChannelId());
        tokenData.setRoomName(mxClient.getRoom(tokenData.getRoomAddress()).getName().orElse(""));
        tokenData.setRoom(StringUtils.defaultIfBlank(tokenData.getRoomName(), tokenData.getRoomAddress()));

        switch (ev.getType()) {
            case OnCreate:
                return Optional.of(makeEmail(tokenData, template, true));
            default:
                log.warn("Unknown subscription event type {}", ev.getType().getId());
                return Optional.of(makeEmail(tokenData, template, false));
        }
    }

    private class TokenData {

        private String key;
        private String timeHour;
        private String timeMin;
        private String timeSec;
        private String senderName;
        private String senderAddress;
        private String senderAvatar;
        private String receiverAddress;
        private String room;
        private String roomName;
        private String roomAddress;
        private String manageUrl;

        TokenData(String key) {
            this.key = key;
        }

        String getKey() {
            return key;
        }

        String getTimeHour() {
            return timeHour;
        }

        void setTimeHour(String timeHour) {
            this.timeHour = timeHour;
        }

        String getTimeMin() {
            return timeMin;
        }

        void setTimeMin(String timeMin) {
            this.timeMin = timeMin;
        }

        String getTimeSec() {
            return timeSec;
        }

        void setTimeSec(String timeSec) {
            this.timeSec = timeSec;
        }

        String getSenderName() {
            return senderName;
        }

        void setSenderName(String senderName) {
            this.senderName = senderName;
        }

        String getSenderAddress() {
            return senderAddress;
        }

        void setSenderAddress(String senderAddress) {
            this.senderAddress = senderAddress;
        }

        String getSenderAvatar() {
            return senderAvatar;
        }

        void setSenderAvatar(String senderAvatar) {
            this.senderAvatar = senderAvatar;
        }

        String getReceiverAddress() {
            return receiverAddress;
        }

        void setReceiverAddress(String receiverAddress) {
            this.receiverAddress = receiverAddress;
        }

        String getRoom() {
            return room;
        }

        void setRoom(String room) {
            this.room = room;
        }

        String getRoomName() {
            return roomName;
        }

        void setRoomName(String roomName) {
            this.roomName = roomName;
        }

        String getRoomAddress() {
            return roomAddress;
        }

        void setRoomAddress(String roomAddress) {
            this.roomAddress = roomAddress;
        }

        String getManageUrl() {
            return manageUrl;
        }

        void setManageUrl(String manageUrl) {
            this.manageUrl = manageUrl;
        }

    }

}
