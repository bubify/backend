package com.uu.au.controllers;

import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;
import com.uu.au.AUPortal;
import com.uu.au.enums.DemonstrationStatus;
import com.uu.au.enums.Role;
import com.uu.au.enums.errors.AuthErrors;
import com.uu.au.enums.errors.GenericRequestListErrors;
import com.uu.au.enums.errors.HelpErrors;
import com.uu.au.enums.errors.UserErrors;
import com.uu.au.models.HelpListCleared;
import com.uu.au.models.HelpRequest;
import com.uu.au.models.Json;
import com.uu.au.models.User;
import com.uu.au.repository.CourseRepository;
import com.uu.au.repository.HelpListClearedRepository;
import com.uu.au.repository.HelpRequestRepository;
import com.uu.au.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
class HelpRequestController {

    @Autowired
    private HelpRequestRepository helpRequests;

    @Autowired
    private UserRepository users;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private WebSocketController webSocketController;

    @Autowired
    private EntityManager entityManager;

    private final Logger logger = LoggerFactory.getLogger(UserController.class);

    public static final String filterStudent = "**,submitters[id,firstName,lastName,verifiedProfilePic,needsProfilePic],helper[id,firstName,lastName]";

    public List<HelpRequest> helpRequestsCurrentCourseInstance() {
        var currentCourseStartDate = courses.currentCourseInstance().getStartDate().atStartOfDay();

        return helpRequests
                .findAll()
                .stream()
                .filter(d -> d.getRequestTime().isAfter(currentCourseStartDate))
                .collect(Collectors.toList());
    }

    @CrossOrigin
    @GetMapping("/helpRequests")
    @PreAuthorize("hasAuthority('Senior_TA') or hasAuthority('Teacher')")
    List<HelpRequest> all() {
        var requests = helpRequests.findAll();
        requests.sort(Comparator.comparing(HelpRequest::getRequestTime));
        return requests;
    }

    @CrossOrigin
    @GetMapping("/helpRequests/active")
    List<HelpRequest> active() {
        return pending();
        //return SquigglyUtils.listify(Squiggly.init(AUPortal.OBJECT_MAPPER, HelpRequestController.filterStudent), pending(), HelpRequest.class);
    }

    List<HelpRequest> pending() {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<HelpRequest> criteriaQuery = criteriaBuilder.createQuery(HelpRequest.class);
        Root<HelpRequest> root = criteriaQuery.from(HelpRequest.class);

        javax.persistence.criteria.Predicate isActiveAndSubmittedOrClaimed = criteriaBuilder.and(
                callIsActiveMethod(root, criteriaBuilder),
                criteriaBuilder.or(
                        criteriaBuilder.equal(root.get("status"), DemonstrationStatus.SUBMITTED),
                        criteriaBuilder.equal(root.get("status"), DemonstrationStatus.CLAIMED)
                )
        );

        criteriaQuery.select(root).where(isActiveAndSubmittedOrClaimed);
        var list = entityManager.createQuery(criteriaQuery).getResultList();
        list.sort(Comparator.comparing(HelpRequest::getRequestTime));
        return list;
    }

   private javax.persistence.criteria.Predicate callIsActiveMethod(Root<HelpRequest> root, CriteriaBuilder criteriaBuilder) {
        LocalDateTime nowMinus24Hours = LocalDateTime.now().minusHours(24);
        return criteriaBuilder.and(
                criteriaBuilder.isNull(root.get("reportTime")),
                criteriaBuilder.greaterThan(root.get("requestTime"), nowMinus24Hours),
                criteriaBuilder.notEqual(root.get("status"), DemonstrationStatus.IN_FLIGHT)
        );
    }

    @CrossOrigin
    @PostMapping("/helpRequests")
    HelpRequest newHelpRequest(@RequestBody HelpRequest newHelpRequest) {
        var result = helpRequests.save(newHelpRequest);
        webSocketController.notifyHelpListSubscribers();
        return SquigglyUtils.objectify(Squiggly.init(AUPortal.OBJECT_MAPPER, HelpRequestController.filterStudent), result, HelpRequest.class);
    }

    private static final ReentrantLock uglyHelpRequestLock = new ReentrantLock();

    @CrossOrigin
    @PostMapping("/askForHelp")
    @Operation(summary = "Creates a new help request populated by the students asking for help")
    public HelpRequest askForHelp(@RequestBody Json.HelpRequest helpRequest) {
        try {
            uglyHelpRequestLock.lock();
            return _askForHelp(helpRequest);
        } finally {
            uglyHelpRequestLock.unlock();
        }
    }
    public HelpRequest _askForHelp(@RequestBody Json.HelpRequest helpRequest) {
        final var newHelpRequest =
                helpRequests.saveAndFlush(HelpRequest
                    .builder()
                    .submitters(helpRequest.getIds().stream().map(users::findOrThrow).collect(Collectors.toSet()))
                    .status(DemonstrationStatus.IN_FLIGHT)
                    .message(helpRequest.getMessage())
                    .zoomRoom(users.currentUser().getZoomRoom())
                    .zoomPassword(helpRequest.getZoomPassword())
                    .physicalRoom(helpRequest.getPhysicalRoom())
                    .build());
        final var usersWithActiveHelpRequests =
                this.helpRequests.usersWithActiveHelpRequestsUpToRequestId(newHelpRequest.getId());

        if (!newHelpRequest.getSubmitters().contains(users.currentUser())) {
            helpRequests.delete(newHelpRequest);
            throw GenericRequestListErrors.currentUserNotInSubmitters();
        }

        var course = courses.currentCourseInstance();
        newHelpRequest.getSubmitters().forEach(user -> {
            if (user.currentCourseInstance().isEmpty() || !user.currentCourseInstance().get().equals(course)) {
                helpRequests.delete(newHelpRequest);
                throw UserErrors.notCurrentlyEnrolled();
            }
        });

        newHelpRequest.getSubmitters().forEach(user -> {
            if (usersWithActiveHelpRequests.contains(user)) {
                helpRequests.delete(newHelpRequest);
                throw HelpErrors.userInMultipleHelpRequest();
            }
        });

        newHelpRequest.setStatus(DemonstrationStatus.SUBMITTED);
        return saveAndNotify(newHelpRequest);
    }

    @CrossOrigin
    @PostMapping("/offerHelp")
    public HelpRequest offerHelp(@RequestBody Json.HelpRequestId helpRequestId) {
        var user = users.currentUser();
        if (user.isTeacher() || user.isCanClaimHelpRequests()) {
            try {
                uglyHelpRequestLock.lock();
                return _offerHelp(helpRequestId);
            } finally {
                uglyHelpRequestLock.unlock();
            }
        } else {
            throw AuthErrors.insufficientPrivileges();
        }
    }

    public HelpRequest _offerHelp(@RequestBody Json.HelpRequestId helpRequestId) {
        var helpRequest = this.helpRequests
                    .findById(helpRequestId.getHelpRequestId())
                    .orElseThrow(HelpErrors::helpRequestNotFound);

            if (helpRequest.isActiveAndSubmitted()) {
                var helper = users.currentUser();

                if (helpRequest.getHelper() == null) {
                    helpRequest.setHelper(helper);
                    helpRequest.setPickupTime(LocalDateTime.now());
                    helpRequest.setStatus(DemonstrationStatus.CLAIMED);
                    this.helpRequests.save(helpRequest);
                }

                webSocketController.notifyHelpClaim(helpRequest.getSubmitters(), helper.getFirstName() + " " + helper.getLastName());
                return saveAndNotify(helpRequest);

            } else {
                return helpRequest;

            }

    }

    @CrossOrigin
    @PostMapping("/markAsDone")
    public HelpRequest helpReceived(@RequestBody Json.HelpRequestId helpRequestId) {
        var user = users.currentUser();
        if (user.isTeacher() || user.isCanClaimHelpRequests()) {
            try {
                uglyHelpRequestLock.lock();
                return _helpReceived(helpRequestId);
            } finally {
                uglyHelpRequestLock.unlock();
            }
        } else {
            throw AuthErrors.insufficientPrivileges();
        }
    }
    public HelpRequest _helpReceived(@RequestBody Json.HelpRequestId helpRequestId) {
        var helpRequest = this.helpRequests
                .findById(helpRequestId.getHelpRequestId())
                .orElseThrow(HelpErrors::helpRequestNotFound);

        if (!helpRequest.isActive()) {
            throw HelpErrors.helpRequestExpired();
        }

        if (!helpRequest.isPickedUp()) {
            throw HelpErrors.helpRequestNotPickedUp();
        }

        if (!users.currentUser().isCanClaimHelpRequests() && users.currentUser().getRole() == Role.STUDENT && !helpRequest.includesSubmitter(users.currentUser())) {
            throw HelpErrors.helpRequestCannotMarkByStudentNotInHelpRequest();
        }

        helpRequest.setStatus(DemonstrationStatus.COMPLETED);
        helpRequest.setReportTime(LocalDateTime.now());
        return saveAndNotify(helpRequest);
    }

    @PostMapping("/helpRequest/unclaim")
    public HelpRequest helpRequestUnclaim(@RequestBody Json.HelpRequestId helpRequestId) {
        var user = users.currentUser();
        if (user.isTeacher() || user.isCanClaimHelpRequests()) {
            try {
                uglyHelpRequestLock.lock();
                return _helpRequestUnclaim(helpRequestId);
            } finally {
                uglyHelpRequestLock.unlock();
            }
        } else {
            throw AuthErrors.insufficientPrivileges();
        }
    }
    public HelpRequest _helpRequestUnclaim(@RequestBody Json.HelpRequestId helpRequestId) {
        var helpRequest = this.helpRequests
                .findById(helpRequestId.getHelpRequestId())
                .orElseThrow(HelpErrors::helpRequestNotFound);

        if (helpRequest.isActiveAndClaimed()) {
            helpRequest.setStatus(DemonstrationStatus.SUBMITTED);
            helpRequest.setHelper(null);
            return saveAndNotify(helpRequest);

        } else {
            return helpRequest;

        }
    }

    @PreAuthorize("hasAuthority('Junior_TA') or hasAuthority('Senior_TA') or hasAuthority('Teacher')")
    @GetMapping("/helpRequests/clearList")
    public void clearAllActiveHelpRequestsUser() {
        try {
            uglyHelpRequestLock.lock();
            _clearAllActiveHelpRequestsUser();
        } finally {
            uglyHelpRequestLock.unlock();
        }
    }

    public void clearAllActiveHelpRequests() {
        try {
            uglyHelpRequestLock.lock();
            _clearAllActiveHelpRequests();
        } finally {
            uglyHelpRequestLock.unlock();
        }
    }

    public void _clearAllActiveHelpRequests() {
        this.helpRequests
                .findAll()
                .stream()
                .filter(HelpRequest::isActiveAndSubmitted)
                .forEach(hr -> {
                    hr.setStatus(DemonstrationStatus.CANCELLED_BY_TEACHER);
                    this.helpRequests.save(hr);
                });

        webSocketController.notifyHelpListCleared();
        webSocketController.notifyHelpListSubscribers();
    }

    public void _clearAllActiveHelpRequestsUser() {
        _clearAllActiveHelpRequests();
        helpListClearedRepository.save(HelpListCleared.builder().user(users.currentUser()).build());
    }


    @Autowired
    HelpListClearedRepository helpListClearedRepository;

    @GetMapping("/helpRequests/cancel/{helpRequestId}")
    public void cancelHelpRequest(@PathVariable Long helpRequestId) {
        try {
            uglyHelpRequestLock.lock();
            _cancelHelpRequest(helpRequestId);
        } finally {
            uglyHelpRequestLock.unlock();
        }
    }

    public void _cancelHelpRequest(@PathVariable Long helpRequestId) {
        final var helpRequest = this.helpRequests.findById(helpRequestId).orElseThrow(HelpErrors::helpRequestNotFound);
        final var currentUser = users.currentUser();

        if (!helpRequest.isActiveAndSubmittedOrClaimed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Help request could not be cancelled");
        }

        if (currentUser.isStudent() && !helpRequest.getSubmitters().contains(currentUser)) {
            throw GenericRequestListErrors.currentUserNotInSubmitters();
        }

        helpRequest.setStatus((currentUser.isStudent()) ? DemonstrationStatus.CANCELLED_BY_STUDENT : DemonstrationStatus.CANCELLED_BY_TEACHER);
        saveAndNotify(helpRequest);
    }

    private HelpRequest saveAndNotify(HelpRequest helpRequest) {
        var result = this.helpRequests.save(helpRequest);
        webSocketController.notifyHelpListSubscribers();
        helpRequests.flush();
        return SquigglyUtils.objectify(Squiggly.init(AUPortal.OBJECT_MAPPER, HelpRequestController.filterStudent), result, HelpRequest.class);
    }
}

