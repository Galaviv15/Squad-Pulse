/**
 * A player's age in completed years on `today` (its local calendar date), from an ISO
 * `yyyy-MM-dd` date of birth. The same rule as the server's Period.between(dateOfBirth,
 * today).getYears(): a year is complete once the month and day are reached, so someone born on
 * 29 February turns a year older on 1 March in a non-leap year. Null for a missing or malformed
 * date.
 *
 * The date is read by its parts, never with new Date("yyyy-MM-dd"), which is UTC midnight and so
 * the previous day in zones west of UTC.
 *
 * Accepted: the server filters by age in the JVM's time zone and the browser shows ages in its
 * own, so around midnight on a birthday a shown age can be one off from the age filter's.
 */
export function ageOn(dateOfBirth: string | null, today: Date): number | null {
  const match = dateOfBirth === null ? null : /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateOfBirth);
  if (match === null) {
    return null;
  }
  const [year, month, day] = match.slice(1).map(Number);
  const todayMonth = today.getMonth() + 1;
  const todayDay = today.getDate();
  const birthdayReached = todayMonth > month || (todayMonth === month && todayDay >= day);
  return today.getFullYear() - year - (birthdayReached ? 0 : 1);
}
