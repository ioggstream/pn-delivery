package it.pagopa.pn.delivery.rest;

import com.fasterxml.jackson.annotation.JsonView;
import it.pagopa.pn.api.dto.InputSearchNotificationDto;
import it.pagopa.pn.api.dto.NotificationSearchRow;
import it.pagopa.pn.api.dto.ResultPaginationDto;
import it.pagopa.pn.api.dto.notification.Notification;
import it.pagopa.pn.api.dto.notification.NotificationJsonViews;
import it.pagopa.pn.api.dto.notification.status.NotificationStatus;
import it.pagopa.pn.api.rest.PnDeliveryRestApi_methodGetSentNotification;
import it.pagopa.pn.api.rest.PnDeliveryRestApi_methodGetSentNotificationDocuments;
import it.pagopa.pn.api.rest.PnDeliveryRestApi_methodSearchSentNotification;
import it.pagopa.pn.api.rest.PnDeliveryRestConstants;
import it.pagopa.pn.commons.exceptions.PnValidationException;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.SenderReadApi;
import it.pagopa.pn.delivery.rest.dto.ResErrorDto;
import it.pagopa.pn.delivery.rest.utils.HandleValidation;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

@RestController
public class PnSentNotificationsController implements SenderReadApi {

    private final NotificationRetrieverService retrieveSvc;
    private final PnDeliveryConfigs cfg;
    public static final String VALIDATION_ERROR_STATUS = "Validation error";

    public PnSentNotificationsController(NotificationRetrieverService retrieveSvc, PnDeliveryConfigs cfg) {
        this.retrieveSvc = retrieveSvc;
        this.cfg = cfg;
    }


    @GetMapping(PnDeliveryRestConstants.SEND_NOTIFICATIONS_PATH)
    public ResultPaginationDto<NotificationSearchRow,String> searchSentNotification(
            @RequestHeader(name = PnDeliveryRestConstants.CX_ID_HEADER ) String senderId,
            @RequestParam(name = "startDate") Instant startDate,
            @RequestParam(name = "endDate") Instant endDate,
            @RequestParam(name = "recipientId", required = false) String recipientId,
            @RequestParam(name = "status", required = false) NotificationStatus status,
            @RequestParam(name = "groups", required = false) String[] groups,
            @RequestParam(name = "subjectRegExp", required = false) String subjectRegExp,
            @RequestParam(name = "iunMatch", required = false) String iunMatch,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "nextPagesKey", required = false) String nextPagesKey
    ) {

        InputSearchNotificationDto searchDto = new InputSearchNotificationDto.Builder()
                .bySender(true)
                .senderReceiverId(senderId)
                .startDate(startDate)
                .endDate(endDate)
                .filterId(recipientId)
                .status(status)
                .groups( groups != null ? Arrays.asList( groups ) : null )
                .subjectRegExp(subjectRegExp)
                .iunMatch(iunMatch)
                .size(size)
                .nextPagesKey(nextPagesKey)
                .build();
        
        return retrieveSvc.searchNotification( searchDto );
    }

    /*@GetMapping(PnDeliveryRestConstants.NOTIFICATION_SENT_PATH)
    @JsonView(value = NotificationJsonViews.Sent.class )
    public Notification getSentNotification(
            @RequestHeader(name = PnDeliveryRestConstants.CX_ID_HEADER ) String paId,
            @PathVariable( name = "iun") String iun,
            @RequestParam( name = "with_timeline", defaultValue = "true", required = false ) boolean withTimeline
    ) {
            return retrieveSvc.getNotificationInformation( iun, withTimeline );
    }*/




    @GetMapping( PnDeliveryRestConstants.NOTIFICATION_SENT_DOCUMENTS_PATH)
    public ResponseEntity<Resource> getSentNotificationDocument(
            @RequestHeader(name = PnDeliveryRestConstants.CX_ID_HEADER ) String paId,
            @PathVariable("iun") String iun,
            @PathVariable("documentIndex") int documentIndex,
            ServerHttpResponse response
    ) {
        if(cfg.isDownloadWithPresignedUrl()) {
            String redirectUrl = retrieveSvc.downloadDocumentWithRedirect(iun, documentIndex);
            //response.setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
            //response.getHeaders().setLocation(URI.create( redirectUrl ));
            //return null;
            response.getHeaders().setContentType( MediaType.APPLICATION_JSON );
            String responseString  = "{ \"url\": \"" + redirectUrl + "\"}";
            Resource resource = new ByteArrayResource( responseString.getBytes(StandardCharsets.UTF_8) );
            return ResponseEntity.ok( resource );
        } else {
            ResponseEntity<Resource> resource = retrieveSvc.downloadDocument(iun, documentIndex);
            return AttachmentRestUtils.prepareAttachment( resource, iun, "doc" + documentIndex );
        }

    }

    @ExceptionHandler({PnValidationException.class})
    public ResponseEntity<ResErrorDto> handleValidationException(PnValidationException ex){
        return HandleValidation.handleValidationException(ex, VALIDATION_ERROR_STATUS);
    }

}
