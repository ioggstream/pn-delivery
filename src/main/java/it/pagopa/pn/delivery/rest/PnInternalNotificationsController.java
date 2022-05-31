package it.pagopa.pn.delivery.rest;

import it.pagopa.pn.commons.exceptions.PnInternalException;
import it.pagopa.pn.commons.log.PnAuditLogBuilder;
import it.pagopa.pn.commons.log.PnAuditLogEvent;
import it.pagopa.pn.commons.log.PnAuditLogEventType;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.InternalOnlyApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationRecipient;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.RequestUpdateStatusDto;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.SentNotification;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.svc.StatusService;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import it.pagopa.pn.delivery.utils.ModelMapperFactory;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
public class PnInternalNotificationsController implements InternalOnlyApi {

    private final NotificationRetrieverService retrieveSvc;
    private final StatusService statusService;
    private final PnDeliveryConfigs cfg;
    private final ModelMapperFactory modelMapperFactory;

    public PnInternalNotificationsController(NotificationRetrieverService retrieveSvc, StatusService statusService, PnDeliveryConfigs cfg, ModelMapperFactory modelMapperFactory) {
        this.retrieveSvc = retrieveSvc;
        this.statusService = statusService;
        this.cfg = cfg;
        this.modelMapperFactory = modelMapperFactory;
    }

    @Override
    public ResponseEntity<SentNotification> getSentNotificationPrivate(String iun) {
        InternalNotification notification = retrieveSvc.getNotificationInformation(iun, false);
        ModelMapper mapper = modelMapperFactory.createModelMapper(InternalNotification.class, SentNotification.class);
        SentNotification sentNotification = mapper.map(notification, SentNotification.class);
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        Map<String, String> logDetailsMap = Map.of(
                "iun", iun,
                "senderDenomination", sentNotification.getSenderDenomination(),
                "senderPaId", sentNotification.getSenderPaId(),
                "senderTaxId", sentNotification.getSenderTaxId()
        );
        PnAuditLogEvent logEvent = auditLogBuilder.before(PnAuditLogEventType.AUD_NT_INSERT, "sentNotification", logDetailsMap);

        int recIdx = 0;
        for (NotificationRecipient rec : sentNotification.getRecipients()) {
            rec.setTaxId(notification.getRecipientIds().get(recIdx));
            recIdx += 1;
        }
        logEvent.generateSuccess("", logDetailsMap).log();
        return ResponseEntity.ok(sentNotification);
    }

    @Override
    public ResponseEntity<Void> updateStatus(RequestUpdateStatusDto requestUpdateStatusDto) {
        String logMessage = String.format("Update status for iun %s", requestUpdateStatusDto.getIun());
        log.info(logMessage);
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        Map<String, String> logDetailsMap = Map.of(
                "iun", requestUpdateStatusDto.getIun()
        );
        PnAuditLogEvent logEvent = auditLogBuilder.before(PnAuditLogEventType.AUD_NT_STATUS, "sentNotification", logDetailsMap);
        try {
            statusService.updateStatus(requestUpdateStatusDto);
            logEvent.generateSuccess(logMessage, logDetailsMap).log();
        } catch (PnInternalException e) {
            logEvent.generateFailure(logMessage, logDetailsMap).log();
        }

        return ResponseEntity.ok().build();
    }
}
