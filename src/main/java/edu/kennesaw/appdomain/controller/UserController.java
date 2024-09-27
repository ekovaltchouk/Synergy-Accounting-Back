package edu.kennesaw.appdomain.controller;

import edu.kennesaw.appdomain.UserType;
import edu.kennesaw.appdomain.dto.*;
import edu.kennesaw.appdomain.entity.ConfirmationToken;
import edu.kennesaw.appdomain.entity.PasswordResetToken;
import edu.kennesaw.appdomain.entity.User;
import edu.kennesaw.appdomain.entity.VerificationToken;
import edu.kennesaw.appdomain.repository.ConfirmationRepository;
import edu.kennesaw.appdomain.repository.TokenRepository;
import edu.kennesaw.appdomain.repository.UserRepository;
import edu.kennesaw.appdomain.repository.VerificationRepository;
import edu.kennesaw.appdomain.service.EmailService;
import edu.kennesaw.appdomain.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@CrossOrigin(origins = "https://synergyaccounting.app", allowCredentials = "true")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private EmailService emailService;
    @Autowired
    private TokenRepository tokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConfirmationRepository confirmationRepository;
    @Autowired
    private VerificationRepository verificationRepository;
    @Autowired
    private AuthenticationManager authenticationManager;

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody RegistrationRequest registrationRequest, HttpServletRequest request) {
        User user = new User();
        user.setEmail(registrationRequest.getEmail());
        user.setFirstName(registrationRequest.getFirstName());
        user.setLastName(registrationRequest.getLastName());
        user.setBirthday(registrationRequest.getBirthday());
        user.setBirthMonth(registrationRequest.getBirthMonth());
        user.setBirthYear(registrationRequest.getBirthYear());
        user.setAddress(registrationRequest.getAddress());
        user.setPassword(registrationRequest.getPassword());
        GregorianCalendar gc = new GregorianCalendar();
        SimpleDateFormat year = new SimpleDateFormat("yy");
        SimpleDateFormat month = new SimpleDateFormat("MM");
        String username = registrationRequest.getFirstName().charAt(0) + registrationRequest.getLastName() + month.format(gc.getTime()) + year.format(gc.getTime());
        if (userRepository.findByUsername(username) != null) {
            int increment = 1;
            while (userRepository.findByUsername(username) != null) {
                username += "-" + increment;
                increment++;
            }
        }
        user.setUsername(username);
        return userService.registerUser(user, registrationRequest.getConfpassword());
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest user, HttpServletRequest request) {
        try {

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getEmail(), user.getPassword())
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            HttpSession session = request.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());

            System.out.println("Session ID: " + session.getId());
            System.out.println("Authenticated User: " + SecurityContextHolder.getContext().getAuthentication().getName());

            return userService.loginUser(user.getEmail(), user.getPassword());

        } catch (BadCredentialsException e) {
            User invalidUser = userRepository.findByEmail(user.getEmail());
            if (invalidUser != null) {
                invalidUser.setFailedLoginAttempts(invalidUser.getFailedLoginAttempts() + 1);
                userRepository.save(invalidUser);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("Invalid username or password!"));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("This email does not exist in our database!"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("An error occurred during login"));
        }
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<MessageResponse> requestResetPassword(@RequestBody EmailObject email, HttpServletRequest request) {
        User user = userService.getUserFromEmail(email.getEmail());
        if (user != null) {
            String token = UUID.randomUUID().toString();
            userService.savePasswordResetToken(user, token);
            String resetLink = "https://synergyaccounting.app/password-reset?token=" + token;
            emailService.sendPasswordResetEmail(user.getEmail(), resetLink);
            return ResponseEntity.ok(new MessageResponse("A link to reset your password has been sent" +
                    " to your email."));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse("Invalid email address."));
    }

    @GetMapping("/password-reset")
    public ResponseEntity<MessageResponse> showPasswordResetForm(@RequestParam("token") String token, HttpServletRequest request) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token);
        if (resetToken == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse("Error: Invalid Password " +
                    "Reset Token\nPlease make a new password reset request."));
        }
        if (resetToken.getExpiryDate().before(new Date())) {
            tokenRepository.delete(resetToken);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse("Error: This Token is Expired" +
                    "\nPlease make a new password reset request."));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-reset")
    public ResponseEntity<MessageResponse> resetPassword(@RequestParam("token") String token, @RequestBody NewPasswordRequest password, HttpServletRequest request) {
        return userService.resetPassword(token, password.getPassword());
    }

    @GetMapping("/confirm-user")
    public ResponseEntity<String> confirmUser(@RequestParam("token") String token, HttpServletRequest request) {
        ConfirmationToken confToken = confirmationRepository.findByToken(token);
        if (confToken == null || confToken.getExpiryDate().before(new Date())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        User user = confToken.getUser();
        user.setUserType(UserType.USER);
        userRepository.save(user);
        confirmationRepository.delete(confToken);
        return ResponseEntity.ok("Token is Valid.");
    }

    @GetMapping("/verify")
    public ResponseEntity<MessageResponse> verifyUser(@RequestParam("token") String token, HttpServletRequest request) {

        // Handle Verification Request

        VerificationToken verifyToken = verificationRepository.findByToken(token);
        if (verifyToken == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse("Error: Invalid Verification" +
                    " Code\nYour account is already verified, or the link is incorrect."));
        }
        if (verifyToken.getExpiryDate().before(new Date())) {
            userRepository.delete(verifyToken.getUser());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse("Error: This verification" +
                    " link has expired.\nYour account has been deleted; you may re-register."));
        }

        User user = verifyToken.getUser();
        user.setIsVerified(true);
        userRepository.save(user);
        verificationRepository.delete(verifyToken);

        // Handle Administrator Confirmation Link

        String confirmToken = UUID.randomUUID().toString();
        userService.saveConfirmationToken(user, confirmToken);
        String confirmLink = "https://synergyaccounting.app/confirm-user?token=" + confirmToken;
        List<User> admins = userRepository.findAll()
                .stream()
                .filter(u -> u.getUserType().equals(UserType.ADMINISTRATOR))
                .collect(Collectors.toList());
        for (int i = 0; i < admins.size(); i += 1) {
            int end = Math.min(admins.size(), i + 1);
            List<User> batch = admins.subList(i, end);
            for (User admin : batch) {
                emailService.sendAdminConfirmEmail(admin.getEmail(), user, confirmLink);
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return ResponseEntity.ok(new MessageResponse("Your account has been verified. You will receive an email once" +
                " your account is approved by an administrator."));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboardSetup(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            System.out.println("Session ID: " + session.getId());
        } else {
            System.out.println("No session found.");
        }
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            System.out.println("CSRF Token in login request: " + csrfToken.getToken());
        } else {
            System.out.println("No CSRF token found in login request.");
        }
        return ResponseEntity.ok().build();
    }

}
