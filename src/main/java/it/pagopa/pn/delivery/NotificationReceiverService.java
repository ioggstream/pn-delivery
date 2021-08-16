package it.pagopa.pn.delivery;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import it.pagopa.pn.api.dto.NewNotificationResponse;
import it.pagopa.pn.api.dto.events.*;
import it.pagopa.pn.api.dto.notification.Notification;
import it.pagopa.pn.api.dto.notification.NotificationAttachment;
import it.pagopa.pn.api.dto.notification.NotificationPaymentInfo;
import it.pagopa.pn.api.dto.notification.NotificationSender;
import it.pagopa.pn.commons.abstractions.FileStorage;
import it.pagopa.pn.commons.abstractions.IdConflictException;
import it.pagopa.pn.delivery.middleware.NewNotificationProducer;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationReceiverService {

	private final Clock clock;
	private final NotificationDao notificationDao;
	private final NewNotificationProducer newNotificationEventProducer;
	private final PnDeliveryConfigs configs;
	private final FileStorage fileStorage;
	private final NewNotificationValidator validator;

	@Autowired
	public NotificationReceiverService(Clock clock, NotificationDao notificationDao,
									   NewNotificationProducer newNotificationEventProducer,
									        FileStorage fileStorage, PnDeliveryConfigs configs ) {
		this.clock = clock;
		this.notificationDao = notificationDao;
		this.newNotificationEventProducer = newNotificationEventProducer;
		this.fileStorage = fileStorage;
		this.configs = configs;
		this.validator = new NewNotificationValidator();
	}

	public NewNotificationResponse receiveNotification(Notification notification) {
		notification = validator.checkNewNotificationBeforeInsert( notification );

		int maxRetryTimes = getIunGenerationMaxRetryNumber();
		String iun = tryMultipleTimesToHandleIunCollision( notification, maxRetryTimes );

		return NewNotificationResponse.builder()
				.iun( iun )
				.paNotificationId( notification.getPaNotificationId() )
				.build();
	}

	private String tryMultipleTimesToHandleIunCollision( Notification notification,
														             int maxIunGenerationRetry) {
		String iun;
		boolean duplicatedIun;
		int iunConflictCounter = 0;
		do {
			iun = generateIun();
			duplicatedIun = tryToSaveNotificationMetadataAndAttachments( notification, iun);

			if( duplicatedIun ) {
				iunConflictCounter += 1;
			}
		}
		while ( duplicatedIun && iunConflictCounter < maxIunGenerationRetry);

		return iun;
	}

	private boolean tryToSaveNotificationMetadataAndAttachments( Notification notification,
																                      String iun) {
		String paId = notification.getSender().getPaId();
		Notification notificationWithIun = notification.toBuilder()
				.iun( iun )
				.sender( NotificationSender.builder()
						.paId( paId )
						.build()
				)
				.build();

		Notification notificationWithCompleteMetadata = saveAttachments( notificationWithIun );

		// - Will be delayed from the receiver
		sendNewNotificationEvent( paId, iun);

		boolean duplicatedIun = this.trySaveNotificationMetadataAndCheckIunCollision(
				                                                 notificationWithCompleteMetadata );
		if ( duplicatedIun ) {
			log.warn( "Duplicated IUN: {}", iun );
		}
		return duplicatedIun;
	}

	private void sendNewNotificationEvent( String paId, String iun) {
		NewNotificationEvent event = buildNewNotificationEvent(iun, paId );
		newNotificationEventProducer.push( event );
	}

	private NewNotificationEvent buildNewNotificationEvent(String iun, String paId ) {
		return NewNotificationEvent.builder()
				.header( StandardEventHeader.builder()
						.iun( iun )
						.eventId( iun + "_start" )
						.createdAt( clock.instant() )
						.eventType( EventType.NEW_NOTIFICATION )
						.publisher( EventPublisher.DELIVERY.name() )
						.build()
				)
				.payload( NewNotificationEvent.Payload.builder()
						.paId( paId )
						.build()
				)
				.build();
	}

	private boolean trySaveNotificationMetadataAndCheckIunCollision(Notification notification) {
		boolean duplicatedIun = false;
		try {
			notificationDao.addNotification( notification );
		}
		catch (IdConflictException exc) {
			duplicatedIun = true;
		}
		return duplicatedIun;
	}


	private int getIunGenerationMaxRetryNumber() {
		Integer maxIunGenerationAttempts = configs.getIunRetry();
		if( maxIunGenerationAttempts == null || maxIunGenerationAttempts < 1 ) {
			maxIunGenerationAttempts = 1;
		}
		return maxIunGenerationAttempts;
	}

	private Notification saveAttachments( Notification notification ) {
		String iun = notification.getIun();

		Notification.NotificationBuilder builder = notification.toBuilder();

		// - Save documents
		saveDocuments( notification, iun, builder );

		// - save F24
		saveF24( notification, iun, builder);
		return builder.build();
	}

	private void saveDocuments(Notification notification, String iun, Notification.NotificationBuilder builder) {
		AtomicInteger index = new AtomicInteger( 0 );
		builder.documents( notification.getDocuments().stream()
				.map( toSave -> {
					String key = String.format("%s/documents/%d", iun, index.getAndIncrement() );
					return saveAndUpdateAttachment( toSave, key );
				})
				.collect(Collectors.toList())
			);
	}

	private void saveF24(Notification notification, String iun, Notification.NotificationBuilder builder) {
		NotificationPaymentInfo paymentsInfo = notification.getPayment();
		if( paymentsInfo != null ) {

			NotificationPaymentInfo.NotificationPaymentInfoBuilder paymentsBuilder;
			paymentsBuilder = notification.getPayment().toBuilder();

			NotificationPaymentInfo.F24 f24 = paymentsInfo.getF24();
			if( f24 != null ) {

				NotificationPaymentInfo.F24.F24Builder f24Builder = f24.toBuilder();

				NotificationAttachment f24FlatRate = f24.getFlatRate();
				if( f24FlatRate != null ) {
					String key = String.format( "%s/f24/flatRate", iun);
					f24Builder.flatRate( saveAndUpdateAttachment( f24FlatRate, key ) );
				}

				NotificationAttachment f24Digital = f24.getDigital();
				if( f24Digital != null ) {
					String key = String.format( "%s/f24/digital", iun);
					f24Builder.digital( saveAndUpdateAttachment( f24Digital, key ) );
				}

				NotificationAttachment f24Analog = f24.getAnalog();
				if( f24Analog != null ) {
					String key = String.format( "%s/f24/analog", iun);
					f24Builder.analog( saveAndUpdateAttachment( f24Analog, key ) );
				}

				paymentsBuilder.f24( f24Builder.build() );
			}
			builder.payment( paymentsBuilder.build() );
		}
	}

	private NotificationAttachment saveAndUpdateAttachment( NotificationAttachment attachment, String key ) {
		String versionId = saveOneAttachmentToFileStorage( key, attachment );
		return updateSavedAttachment( attachment, versionId );
	}

	private NotificationAttachment updateSavedAttachment( NotificationAttachment attachment, String versionId ) {
		return attachment.toBuilder()
				.savedVersionId(
						versionId
				)
				.build();
	}

	private String saveOneAttachmentToFileStorage( String key, NotificationAttachment attachment ) {

		Map<String, String> metadata = new HashMap<>();
		metadata.put("content-type", attachment.getContentType() );
		metadata.put("sha256", attachment.getDigests().getSha256() );

		// FIXME check sha256

		byte[] body = Base64.getDecoder().decode( attachment.getBody() );

		String versionId = fileStorage.putFileVersion( key, new ByteArrayInputStream( body ), body.length, metadata );
		log.debug("Save attachment {} with version {}", key, versionId );
		return versionId;
	}

	private String generateIun() {
		String uuid = UUID.randomUUID().toString();
		Instant now = Instant.now(clock);
		OffsetDateTime nowUtc = now.atOffset( ZoneOffset.UTC );
		int year = nowUtc.get( ChronoField.YEAR_OF_ERA);
		int month = nowUtc.get( ChronoField.MONTH_OF_YEAR);
		return String.format("%04d%02d-%s", year, month, uuid);
	}

}
