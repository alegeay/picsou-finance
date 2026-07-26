package com.picsou.config;

import com.picsou.service.GroupamaEsSyncService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Recovers persisted jobs before StartupSyncService queues startup work. */
@Component
@Order(0)
public class GroupamaEsSyncRecovery implements ApplicationRunner {
    private final GroupamaEsSyncService syncService;

    public GroupamaEsSyncRecovery(GroupamaEsSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncService.recoverInterruptedSyncs();
    }
}
