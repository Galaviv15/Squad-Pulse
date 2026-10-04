package com.squadpulse.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The club's staff users, for the Club Manager's staff-management screen (KAN-36).
 *
 * <p><b>Club isolation.</b> The users come from the club-scoped {@link UserRepository#findAll()}
 * (routed through {@code common.ClubScopedRepositoryImpl}, which filters on the clubId {@link
 * JwtAuthenticationFilter} took from the access token), and the photo flags from {@link
 * StaffPhotoService#userIdsWithPhoto()}, which the image storage restricts to the same clubId.
 * Nothing is read from the request, so another club's users and photos can never appear or
 * influence a flag.
 *
 * <p><b>Exactly two queries per call</b>, however many users the club has: the users, and the ids
 * of those with a photo. Never a per-user {@link StaffPhotoService#hasPhoto} lookup. Filtering and
 * sorting happen in memory (docs/spec.md section 03) — a club has a handful of staff users, so
 * there's no pagination either.
 *
 * <p><b>Everyone in the club</b>, the caller included: deactivated users ({@code active == false}),
 * so the Club Manager can see and later re-activate them, and invited users who haven't set a
 * password yet ({@code activated == false}), so a pending invitation is visible. Both are flagged
 * in the response rather than hidden.
 *
 * <p><b>Read-only.</b> No user is ever saved, so this can't conflict with a concurrent write to a
 * user (KAN-24) and never changes a user's {@code version} or {@code updatedAt}.
 */
@Service
class StaffListService {

  /**
   * Active users first, then by {@code fullName}, then by {@code id} so the order is deterministic
   * even for equal names. A stored document may lack a name or id, so those sort last. Plain {@link
   * String} order, without Hebrew collation — the same accepted limitation as the squad list.
   */
  static final Comparator<User> STAFF_ORDER =
      Comparator.comparing((User user) -> !user.isActive())
          .thenComparing(User::getFullName, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final UserRepository userRepository;
  private final StaffPhotoService staffPhotoService;

  StaffListService(UserRepository userRepository, StaffPhotoService staffPhotoService) {
    this.userRepository = userRepository;
    this.staffPhotoService = staffPhotoService;
  }

  /** Every user of the caller's club, in {@link #STAFF_ORDER}. */
  List<UserResponse> list() {
    List<User> users = userRepository.findAll();
    Set<String> withPhoto = staffPhotoService.userIdsWithPhoto();
    return users.stream()
        .sorted(STAFF_ORDER)
        .map(user -> UserResponse.from(user, withPhoto.contains(user.getId())))
        .toList();
  }
}
