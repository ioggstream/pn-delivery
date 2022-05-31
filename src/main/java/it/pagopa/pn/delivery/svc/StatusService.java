package it.pagopa.pn.delivery.svc;

import it.pagopa.pn.commons.exceptions.PnInternalException;
import it.pagopa.pn.commons.log.PnAuditLogBuilder;
import it.pagopa.pn.commons.log.PnAuditLogEvent;
import it.pagopa.pn.commons.log.PnAuditLogEventType;
import it.pagopa.pn.delivery.generated.openapi.clients.datavault.model.RecipientType;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationRecipient;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationStatus;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.RequestUpdateStatusDto;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import it.pagopa.pn.delivery.middleware.notificationdao.NotificationMetadataEntityDao;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationMetadataEntity;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.pnclient.datavault.PnDataVaultClientImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StatusService {
    private final NotificationDao notificationDao;
    private final NotificationMetadataEntityDao notificationMetadataEntityDao;
    private final PnDataVaultClientImpl dataVaultClient;

    public StatusService(NotificationDao notificationDao,
                         NotificationMetadataEntityDao notificationMetadataEntityDao, PnDataVaultClientImpl dataVaultClient) {
        this.notificationDao = notificationDao;
        this.notificationMetadataEntityDao = notificationMetadataEntityDao;
        this.dataVaultClient = dataVaultClient;
    }

    public void updateStatus(RequestUpdateStatusDto dto) {
        Optional<InternalNotification> notificationOptional = notificationDao.getNotificationByIun(dto.getIun());
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        String iun = dto.getIun();
        if (notificationOptional.isPresent()) {
            InternalNotification notification = notificationOptional.get();
            String logMessage = String.format("Notification is present %s for iun %s", notification.getPaProtocolNumber(), dto.getIun());
            Map<String, String> logDetailsMap = Map.of(
                    "iun", iun,
                    "paProtocolNumber", notification.getPaProtocolNumber()
            );
            PnAuditLogEvent logEvent = auditLogBuilder.before(PnAuditLogEventType.AUD_NT_STATUS, logMessage, logDetailsMap);
            logEvent.generateSuccess().log();
            List<NotificationMetadataEntity> nextMetadataEntry = computeMetadataEntry(notification.getNotificationStatus(), notification);
            nextMetadataEntry.forEach(notificationMetadataEntityDao::put);
        } else {
            String logMessage = String.format("Try to update status for non existing iun %s", iun);
            Map<String, String> logDetailsMap = Map.of(
                    "iun", iun
            );
            PnAuditLogEvent logEvent = auditLogBuilder.before(PnAuditLogEventType.AUD_NT_STATUS, logMessage, logDetailsMap);
            logEvent.generateFailure(logMessage).log();
            throw new PnInternalException(logMessage);
        }
    }

    private List<NotificationMetadataEntity> computeMetadataEntry(NotificationStatus lastStatus, InternalNotification notification) {
        String creationMonth = extractCreationMonth(notification.getSentAt().toInstant());

        List<String> opaqueTaxIds = new ArrayList<>();
        for (NotificationRecipient recipient : notification.getRecipients()) {
            opaqueTaxIds.add(dataVaultClient.ensureRecipientByExternalId(RecipientType.fromValue(recipient.getRecipientType().getValue()), recipient.getTaxId()));
        }

        return opaqueTaxIds.stream()
                .map(recipientId -> this.buildOneSearchMetadataEntry(notification, lastStatus, recipientId, opaqueTaxIds, creationMonth))
                .collect(Collectors.toList());
    }

    private NotificationMetadataEntity buildOneSearchMetadataEntry(
            InternalNotification notification,
            NotificationStatus lastStatus,
            String recipientId,
            List<String> recipientsIds,
            String creationMonth
    ) {
        int recipientIndex = recipientsIds.indexOf(recipientId);

        return NotificationMetadataEntity.builder()
                .notificationStatus(lastStatus != null ? lastStatus.toString() : null)
                .senderId(notification.getSenderPaId())
                .recipientId(recipientId)
                .sentAt(notification.getSentAt().toInstant())
                .notificationGroup(notification.getGroup())
                .recipientIds(recipientsIds)
                .tableRow(Map.ofEntries(
                        Map.entry("iun", notification.getIun()),
                        Map.entry("recipientsIds", recipientsIds.toString()),
                        Map.entry("paProtocolNumber", notification.getPaProtocolNumber()),
                        Map.entry("subject", notification.getSubject())))
                .senderId_recipientId(createConcatenation(notification.getSenderPaId(), recipientId))
                .senderId_creationMonth(createConcatenation(notification.getSenderPaId(), creationMonth))
                .recipientId_creationMonth(createConcatenation(recipientId, creationMonth))
                .iun_recipientId(createConcatenation(notification.getIun(), recipientId))
                .recipientOne(recipientIndex <= 0)
                .build();
    }

    private String createConcatenation(String s1, String s2) {
        return s1 + "##" + s2;
    }

    private String extractCreationMonth(Instant sentAt) {
        String sentAtString = sentAt.toString();
        String[] splitSentAt = sentAtString.split("-");
        return splitSentAt[0] + splitSentAt[1];
    }

}
