package com.uu.au.controllers;

import com.uu.au.enums.errors.UserErrors;
import com.uu.au.models.*;
import com.uu.au.repository.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;

@Controller
@Profile("test")
public class TestController {
    @Autowired
    AuthController authController;

    @Autowired
    UserController userController;

    @Autowired
    UserRepository users;

    @Autowired
    CourseRepository course;

    @Autowired
    EnrolmentRepository enrolment;

    @Autowired
    HelpRequestRepository helpRequestRepository;

    private final Logger logger = LoggerFactory.getLogger(DevelopmentController.class);

    @Scheduled(fixedRate = 100)
    void integrityCheck() {
        var usersActiveOnHelpListMultiSet = new ArrayList<User>();
        var usersActiveOnHelpListSet = new HashSet<User>();

        var hrs = helpRequestRepository.findAll().stream().filter(HelpRequest::isActiveAndSubmittedOrClaimed).collect(Collectors.toList());
        hrs.forEach(hr -> {
            usersActiveOnHelpListMultiSet.addAll(hr.getSubmitters());
            usersActiveOnHelpListSet.addAll(hr.getSubmitters());
        });

        if (usersActiveOnHelpListMultiSet.size() == usersActiveOnHelpListSet.size()) {
            /// logger.info("INTEGRITY OK");
        } else {
            var sb = new StringBuilder();
            sb.append("<<---------------------------");
            hrs.forEach(hr -> {
                sb.append(hr.getId());
                sb.append("\t");
                sb.append(hr.getRequestTime());
                sb.append("\t");
                sb.append(hr.getStatus());
                sb.append("\t");
                sb.append(hr.getSubmitters().stream().map(User::getUserName).collect(Collectors.joining(", ")));
                sb.append("\n");
            });
            sb.append("--------------------------->>");
            logger.info(sb.toString());

            System.exit(-1);


            logger.error("Multiple activity on help list detected: " +
                    usersActiveOnHelpListMultiSet.stream().map(User::getUserName).collect(Collectors.joining(", ")));
/*
            for (var user : usersActiveOnHelpListMultiSet) {
                var usersActiveHelpRequests = helpRequestRepository.findAll().stream().filter(HelpRequest::isActiveAndSubmittedOrClaimed).filter(hr -> hr.getSubmitters().contains(user)).collect(Collectors.toSet());
                for (var helpRequest : usersActiveHelpRequests) {
                    logger.info(helpRequest.toString());
                }
            }
*/
        }
    }

    @CrossOrigin
    @GetMapping("/su")
    public @ResponseBody String devConsumeToken(@RequestParam String username) {
        Long id = users.findByUserNameOrThrow(username).getId();
        var user = authController.installUser(id);
        var dbUser = users.findByUserNameOrThrow(username);

        if (user.isPresent()) {
            if (dbUser.isStudent() && dbUser.currentEnrolment().isEmpty()) {
                throw UserErrors.enrolmentNotFound();
            } else if (dbUser.isSeniorTAOrTeacher() && dbUser.currentEnrolment().isEmpty()) {
                var autoEnroll = Enrolment.builder().courseInstance(course.currentCourseInstance()).achievementsUnlocked(new HashSet<>()).achievementsPushedBack(new HashSet<>()).build();
                dbUser.getEnrolments().add(autoEnroll);
                users.save(dbUser);
                enrolment.save(autoEnroll);
            }

            dbUser.setLastLogin(LocalDateTime.now());
            users.save(dbUser);
            return user.get().getToken();
        } else {
            throw UserErrors.malformedUserName("No user with username " + username);
        }
    }
}
