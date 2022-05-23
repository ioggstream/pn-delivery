package it.pagopa.pn.delivery.middleware.notificationdao;



import it.pagopa.pn.commons.abstractions.IdConflictException;
import it.pagopa.pn.commons.abstractions.impl.MiddlewareTypes;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationSearchRow;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationEntity;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.svc.search.PnLastEvaluatedKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Component
@Slf4j
public class NotificationDaoDynamo implements NotificationDao {

    private final NotificationEntityDao entityDao;
    private final NotificationMetadataEntityDao metadataEntityDao;
    private final DtoToEntityNotificationMapper dto2entityMapper;
    private final EntityToDtoNotificationMapper entity2DtoMapper;

    public NotificationDaoDynamo(
            NotificationEntityDao entityDao,
            NotificationMetadataEntityDao metadataEntityDao, DtoToEntityNotificationMapper dto2entityMapper,
            EntityToDtoNotificationMapper entity2DtoMapper) {
        this.entityDao = entityDao;
        this.metadataEntityDao = metadataEntityDao;
        this.dto2entityMapper = dto2entityMapper;
        this.entity2DtoMapper = entity2DtoMapper;
    }

    @Override
    public void addNotification(InternalNotification internalNotification) throws IdConflictException {

        NotificationEntity entity = dto2entityMapper.dto2Entity(internalNotification);
        entityDao.putIfAbsent( entity );
    }

    @Override
    public Optional<InternalNotification> getNotificationByIun(String iun) {
        Key keyToSearch = Key.builder()
                .partitionValue(iun)
                .build();
        return entityDao.get( keyToSearch )
                .map( entity2DtoMapper::entity2Dto );
    }

    @Override
    public ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> searchForOneMonth(InputSearchNotificationDto inputSearchNotificationDto, String indexName, String partitionValue, int size, PnLastEvaluatedKey lastEvaluatedKey) {
        return this.metadataEntityDao.searchForOneMonth( inputSearchNotificationDto, indexName, partitionValue, size, lastEvaluatedKey );
    }


    Predicate<String> buildRegexpPredicate(String subjectRegExp) {
        Predicate<String> matchSubject;
        if (subjectRegExp != null) {
            matchSubject = Objects::nonNull;
            matchSubject = matchSubject.and(Pattern.compile("^" + subjectRegExp + "$").asMatchPredicate());
        } else {
            matchSubject = x -> true;
        }
        return matchSubject;
    }
}
