package utils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Customer words for each scenario: 20 feedback replies per test, plus the fault reported at
 * check-in. Each test picks one at random, so every run exercises the AI scorer with different
 * wording.
 *
 * Each pool is written to land firmly in its route:
 *  - POSITIVE: clearly pleased, no complaint.            -> Ready to post
 *  - FIRST_COMPLAINT: clearly unhappy but minor (delays, untidiness, slow communication),
 *    never damage, money lost, safety or threats, so severity stays below 4.  -> Private drafts
 *  - REPEAT_COMPLAINT: mild on purpose; only the customer's history escalates it. -> Escalated
 *  - SERIOUS_COMPLAINT: damage, safety or money lost, severity 4 or 5.       -> Escalated
 *  - UNREADABLE: no meaning at all.                                          -> Needs review
 */
public final class ReplyLibrary {

    public static final List<String> POSITIVE = List.of(
            "Great service, the car drives perfectly and the staff were really friendly.",
            "Excellent work. My brakes feel brand new and the job was done on time.",
            "Very happy with the repair. The engine runs smoothly now. Thank you!",
            "The team were professional and polite, and the car was ready when promised.",
            "Fantastic job on the air conditioning. It is blowing cold again. Well done.",
            "I am impressed. Clear explanation of the fault and a fair price. I will be back.",
            "Superb service from start to finish. The car feels like new.",
            "Quick, honest and friendly. The noise from the suspension is completely gone.",
            "Thank you for the excellent work on my gearbox. It shifts perfectly now.",
            "The best garage I have used in Lagos. Fast service and great customer care.",
            "Really pleased. They fixed the problem the first time and kept me updated.",
            "Wonderful experience. The mechanic took time to explain everything to me.",
            "Brilliant work, the car starts first time every morning now. Highly recommend.",
            "Everything was perfect. Clean waiting area, friendly staff and a great repair.",
            "Five stars. The steering is smooth and the car was washed before I collected it.",
            "Delighted with the service. Done on time and exactly as quoted.",
            "Great communication and great workmanship. My car has never driven better.",
            "Very satisfied. The battery and alternator issue is fully sorted. Thank you.",
            "The staff were welcoming and the repair was spot on. Keep it up!",
            "Outstanding service. Honest advice and the car is running beautifully.");

    public static final List<String> FIRST_COMPLAINT = List.of(
            "The car was ready two hours later than promised.",
            "I waited much longer than I was told. Nobody called to explain the delay.",
            "The waiting area was untidy and there was nowhere clean to sit.",
            "Nobody called me when the car was ready. I had to keep phoning the branch.",
            "The repair took a day longer than the time I was given.",
            "The car was returned with dusty seats and fingerprints on the dashboard.",
            "I was kept waiting at the front desk for a long time before anyone attended to me.",
            "The update messages were slow and I had to chase for information.",
            "The car was not washed even though I was told it would be.",
            "Collection was disorganised. It took forty minutes to find my keys.",
            "The staff were a bit slow to explain what had been done to the car.",
            "I was promised a call back about the quote and it never came.",
            "The reception area was hot and stuffy while I waited.",
            "My appointment started an hour late with no apology.",
            "The invoice was confusing and nobody explained the charges clearly.",
            "The car seat had been moved and the radio stations were changed. A bit annoying.",
            "It was hard to get through on the phone to check on my car.",
            "The job was finished late in the evening, not in the afternoon as agreed.",
            "There was grease on the door handle when I collected the car.",
            "The service was slower than my last visit and nobody told me why.");

    public static final List<String> REPEAT_COMPLAINT = List.of(
            "The waiting area was a bit untidy.",
            "It was a little slow at reception this time.",
            "The car came back slightly dusty inside.",
            "I waited a bit longer than expected again.",
            "Nobody called when the car was ready, again.",
            "The paperwork took a while to sort out.",
            "The car was not quite as clean as I hoped.",
            "It took some time to get the keys back.",
            "The front desk was a bit disorganised today.",
            "The update about the car came a little late.",
            "The reception was hot while I waited.",
            "There was a small delay before anyone attended to me.",
            "The invoice was slightly unclear.",
            "My call back came later than promised.",
            "The car was ready a bit later than promised.",
            "The car mats were left a little dirty.",
            "Collection took longer than it should have.",
            "Communication was a bit slow this time.",
            "The seat position had been changed again.",
            "I had to ask twice for an update.");

    public static final List<String> SERIOUS_COMPLAINT = List.of(
            "The brakes failed on the way home from your garage. This is dangerous.",
            "My steering locked on the expressway the day after your repair. I could have been killed.",
            "You scratched the whole side of my car and nobody owned up to it.",
            "The engine caught fire two days after your mechanic worked on it.",
            "A wheel came loose on the road after your service. The nuts were not tightened.",
            "You charged me for new parts but the old worn parts are still on the car. I want my money back.",
            "My windscreen was cracked while the car was in your workshop and you refused to fix it.",
            "The car now leaks fuel after your repair. This is a serious safety risk.",
            "Your staff were abusive and threatened me when I asked about the bill.",
            "The airbag warning light came on after your repair and the airbag is now disabled.",
            "My car was dented in your car park and the branch manager refused to take responsibility.",
            "I was overcharged by over two hundred thousand naira for work that was never done.",
            "The brakes are making a grinding noise since your service and the car barely stops.",
            "Someone took my laptop from the car while it was with you. I am reporting this to the police.",
            "Your mechanic damaged the gearbox and now the car will not move at all.",
            "The tyre you fitted burst on the motorway. My family was in the car. Unacceptable.",
            "Oil was pouring out of the engine the same day I collected the car. The engine is now damaged.",
            "The car overheated and the engine seized right after your repair. It needs a new engine.",
            "You lost my spare key and charged me again for the same repair. I am taking legal action.",
            "The headlights stopped working on the way home at night after your electrical repair. Very dangerous.");

    public static final List<String> UNREADABLE = List.of(
            "k ?? blue 12",
            "asdf 12 ok ??? blue",
            "zz 9 ?? pp",
            "qwe !! 77 lorem",
            "... ?? 4 4 4",
            "blorp 3 yes no 9",
            "xx yy zz 00",
            "hmm 12 ?? purple",
            "lkj lkj 55 ..",
            "abc 123 ?? !!",
            "ooo 7 7 ?? kk",
            "ppp qqq 1 2 3",
            "?? ?? ok 8",
            "test test 999 ??",
            "fgh 4 ? blue",
            "mmm 22 nnn ??",
            "z 1 z 2 z 3",
            "red 5 ?? jj",
            "wq 0 0 ??",
            "ty ty 31 ?? b");

    /** Ordinary faults reported at check-in. */
    public static final List<String> CHECK_IN_ISSUES = List.of(
            "Brake pads squeal when stopping.",
            "Air conditioning is not blowing cold air.",
            "Engine light is on and the car shakes when idling.",
            "Full service and oil change.",
            "Car pulls to the left when driving.",
            "Battery keeps going flat overnight.",
            "Knocking noise from the front suspension over bumps.",
            "Gearbox is slow to change from first to second.",
            "Headlights are dim and one indicator is not working.",
            "Car overheats in traffic.");

    /**
     * Long, clearly negative check-in descriptions for the unreadable-reply test. They prove the
     * scorer judges only the reply: if it scored this text instead, the reply would be routed as
     * a complaint rather than sent for review.
     */
    public static final List<String> LONG_NEGATIVE_CHECK_IN_ISSUES = List.of(
            "Kia has too many issues. The steering is not working as expected and it's a bit loose, "
                    + "the brakes squeal and the engine light keeps coming on. Very frustrating car.",
            "Terrible problems with this car. The gearbox jerks, the air conditioning is dead and "
                    + "there is a burning smell from the engine after short drives.",
            "The car is in a bad state. It overheats in traffic, the suspension knocks loudly and "
                    + "the battery dies every few days. I am fed up with it.");

    private ReplyLibrary() {
    }

    public static String randomFrom(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
