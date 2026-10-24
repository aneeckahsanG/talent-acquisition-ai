package com.talentai.admin.repository;

import com.talentai.admin.entity.AvailabilityWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindow, Long> {

    @Query("select w from AvailabilityWindow w where w.startTime < :to and w.endTime > :from order by w.startTime")
    List<AvailabilityWindow> findOverlapping(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<AvailabilityWindow> findByEndTimeAfterOrderByStartTimeAsc(LocalDateTime after);
}
