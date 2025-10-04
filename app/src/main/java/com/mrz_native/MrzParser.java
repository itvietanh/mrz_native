package com.mrz_native;

public class MrzParser {

    public static class ParsedMrz {
        public final String documentType;
        public final String issuingCountry;
        public final String name;
        public final String documentNumber;
        public final String nationality;
        public final String dob;
        public final String gender;
        public final String expiryDate;
        public final String personalNumber;

        public ParsedMrz(String documentType, String issuingCountry, String name, String documentNumber,
                         String nationality, String dob, String gender, String expiryDate, String personalNumber) {
            this.documentType = documentType;
            this.issuingCountry = issuingCountry;
            this.name = name;
            this.documentNumber = documentNumber;
            this.nationality = nationality;
            this.dob = dob;
            this.gender = gender;
            this.expiryDate = expiryDate;
            this.personalNumber = personalNumber;
        }
    }

    // ---------------------------
    // ICAO 9303 helpers
    // ---------------------------
    private static int charToMrzValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'Z') return 10 + (c - 'A');
        return 0; // '<' and any other treated as 0 per spec
    }

    private static int computeCheckSum(String data) {
        // Weights repeat 7,3,1
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            int v = charToMrzValue(data.charAt(i));
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }

    private static boolean isCheckDigitValid(String data, char checkChar) {
        if (checkChar < '0' || checkChar > '9') return false;
        int expected = computeCheckSum(data);
        return expected == (checkChar - '0');
    }

    private static boolean isDigits(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isValidYYMMDD(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6 || !isDigits(yymmdd)) return false;
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        if (mm < 1 || mm > 12) return false;
        if (dd < 1 || dd > 31) return false;
        return true;
    }

    private static String padRight(String s, int len) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append('<');
        return sb.substring(0, len);
    }

    /**
     * ---------------------------
     * MRZ TYPE TD3 - Passport (2 lines, 44 chars)
     * ---------------------------
     * Example:
     * P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
     * L898902C36UTO7408122F1204159ZE184226B<<<<<<1
     */
    public static ParsedMrz parseTD3(String l1, String l2) {
        if (l1 == null || l2 == null) return null;
        l1 = padRight(l1.toUpperCase(), 44);
        l2 = padRight(l2.toUpperCase(), 44);

        try {
            String documentType = l1.substring(0, 2);
            String issuingCountry = l1.substring(2, 5);
            String[] names = l1.substring(5, 44).split("<<");
            String surname = names.length > 0 ? names[0].replace('<', ' ').trim() : "";
            String givenNames = names.length > 1 ? names[1].replace('<', ' ').trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            String docNumberField = l2.substring(0, 9);
            char docNumberCheck = l2.charAt(9);
            String nationality = l2.substring(10, 13);
            String dob = l2.substring(13, 19);
            char dobCheck = l2.charAt(19);
            String gender = l2.substring(20, 21);
            String expiryDate = l2.substring(21, 27);
            char expiryCheck = l2.charAt(27);
            String personalNumberField = l2.substring(28, 42);
            char personalCheck = l2.charAt(42);
            char compositeCheck = l2.charAt(43);

            boolean c1 = isCheckDigitValid(docNumberField, docNumberCheck);
            boolean c2 = isCheckDigitValid(dob, dobCheck) && isValidYYMMDD(dob);
            boolean c3 = isCheckDigitValid(expiryDate, expiryCheck) && isValidYYMMDD(expiryDate);
            boolean c4 = isCheckDigitValid(personalNumberField, personalCheck) || personalCheck == '<';
            String compositeData = docNumberField + docNumberCheck + dob + dobCheck + expiryDate + expiryCheck + personalNumberField + personalCheck;
            boolean c5 = isCheckDigitValid(compositeData, compositeCheck);
            if (!(c1 && c2 && c3 && c4 && c5)) return null;

            String documentNumber = docNumberField.replace("<", "");
            String personalNumber = personalNumberField.replace("<", "");

            return new ParsedMrz(documentType, issuingCountry, fullName, documentNumber,
                    nationality, dob, gender, expiryDate, personalNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * TD3 relaxed: accept when core checks pass (docNumber, dob, expiry). Useful to tolerate OCR noise.
     */
    public static ParsedMrz parseTD3Relaxed(String l1, String l2) {
        if (l1 == null || l2 == null) return null;
        l1 = padRight(l1.toUpperCase(), 44);
        l2 = padRight(l2.toUpperCase(), 44);
        try {
            String documentType = l1.substring(0, 2);
            String issuingCountry = l1.substring(2, 5);
            String[] names = l1.substring(5, 44).split("<<");
            String surname = names.length > 0 ? names[0].replace('<', ' ').trim() : "";
            String givenNames = names.length > 1 ? names[1].replace('<', ' ').trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            String docNumberField = l2.substring(0, 9);
            char docNumberCheck = l2.charAt(9);
            String nationality = l2.substring(10, 13);
            String dob = l2.substring(13, 19);
            char dobCheck = l2.charAt(19);
            String gender = l2.substring(20, 21);
            String expiryDate = l2.substring(21, 27);
            char expiryCheck = l2.charAt(27);
            String personalNumberField = l2.substring(28, 42);

            boolean c1 = isCheckDigitValid(docNumberField, docNumberCheck);
            boolean c2 = isCheckDigitValid(dob, dobCheck) && isValidYYMMDD(dob);
            boolean c3 = isCheckDigitValid(expiryDate, expiryCheck) && isValidYYMMDD(expiryDate);
            if (!(c1 && c2 && c3)) return null;

            String documentNumber = docNumberField.replace("<", "");
            String personalNumber = personalNumberField.replace("<", "");

            return new ParsedMrz(documentType, issuingCountry, fullName, documentNumber,
                    nationality, dob, gender, expiryDate, personalNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ---------------------------
     * MRZ TYPE TD2 - Visa (2 lines, 36 chars)
     * ---------------------------
     * Example:
     * V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
     * L898902C36UTO7408122F1204159<<<<<<<<<<<<<<06
     */
    public static ParsedMrz parseTD2(String l1, String l2) {
        if (l1 == null || l2 == null) return null;
        l1 = padRight(l1.toUpperCase(), 36);
        l2 = padRight(l2.toUpperCase(), 36);

        try {
            String documentType = l1.substring(0, 2);
            String issuingCountry = l1.substring(2, 5);
            String[] names = l1.substring(5, 36).split("<<");
            String surname = names.length > 0 ? names[0].replace('<', ' ').trim() : "";
            String givenNames = names.length > 1 ? names[1].replace('<', ' ').trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            String docNumberField = l2.substring(0, 9);
            char docNumberCheck = l2.charAt(9);
            String nationality = l2.substring(10, 13);
            String dob = l2.substring(13, 19);
            char dobCheck = l2.charAt(19);
            String gender = l2.substring(20, 21);
            String expiryDate = l2.substring(21, 27);
            char expiryCheck = l2.charAt(27);
            String personalNumberField = l2.substring(28, 35);
            char personalCheck = l2.charAt(35);

            boolean c1 = isCheckDigitValid(docNumberField, docNumberCheck);
            boolean c2 = isCheckDigitValid(dob, dobCheck) && isValidYYMMDD(dob);
            boolean c3 = isCheckDigitValid(expiryDate, expiryCheck) && isValidYYMMDD(expiryDate);
            boolean c4 = isCheckDigitValid(personalNumberField, personalCheck) || personalCheck == '<';
            if (!(c1 && c2 && c3 && c4)) return null;

            String documentNumber = docNumberField.replace("<", "");
            String personalNumber = personalNumberField.replace("<", "");

            return new ParsedMrz(documentType, issuingCountry, fullName, documentNumber,
                    nationality, dob, gender, expiryDate, personalNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ---------------------------
     * MRZ TYPE TD1 - ID Card (3 lines, 30 chars)
     * ---------------------------
     * Example:
     * I<UTOD231458907<<<<<<<<<<<<<<<
     * 7408122F1204159UTO<<<<<<<<<<<6
     * ERIKSSON<<ANNA<MARIA<<<<<<<<<
     */
    public static ParsedMrz parseTD1(String l1, String l2, String l3) {
        if (l1 == null || l2 == null || l3 == null) return null;
        l1 = padRight(l1.toUpperCase(), 30);
        l2 = padRight(l2.toUpperCase(), 30);
        l3 = padRight(l3.toUpperCase(), 30);

        try {
            String documentType = l1.substring(0, 2);
            String issuingCountry = l1.substring(2, 5);
            String docNumberField = l1.substring(5, 14);
            char docNumberCheck = l1.charAt(14);
            String optional1 = l1.substring(15, 30);

            String dob = l2.substring(0, 6);
            char dobCheck = l2.charAt(6);
            String gender = l2.substring(7, 8);
            String expiryDate = l2.substring(8, 14);
            char expiryCheck = l2.charAt(14);
            String nationality = l2.substring(15, 18);
            String optional2 = l2.substring(18, 29);
            char compositeCheck = l2.charAt(29);

            String[] names = l3.substring(0, 30).split("<<");
            String surname = names.length > 0 ? names[0].replace('<', ' ').trim() : "";
            String givenNames = names.length > 1 ? names[1].replace('<', ' ').trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            boolean c1 = isCheckDigitValid(docNumberField, docNumberCheck);
            boolean c2 = isCheckDigitValid(dob, dobCheck) && isValidYYMMDD(dob);
            boolean c3 = isCheckDigitValid(expiryDate, expiryCheck) && isValidYYMMDD(expiryDate);

            // Composite check (TD1 varies by issuing state). Try both common formulas to be robust.
            String compA = docNumberField + docNumberCheck + dob + dobCheck + expiryDate + expiryCheck + optional1;
            String compB = docNumberField + dob + expiryDate + optional1 + optional2; // fallback variant seen in wild
            boolean c4 = isCheckDigitValid(compA, compositeCheck) || isCheckDigitValid(compB, compositeCheck);
            if (!(c1 && c2 && c3 && c4)) return null;

            String documentNumber = docNumberField.replace("<", "");
            String personalNumber = (optional1 + optional2).replace("<", "");

            return new ParsedMrz(documentType, issuingCountry, fullName, documentNumber,
                    nationality, dob, gender, expiryDate, personalNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tự động nhận dạng loại MRZ (TD1/TD2/TD3)
     */
    public static ParsedMrz autoDetect(String... lines) {
        if (lines == null) return null;

        // Loại bỏ dòng rỗng
        int count = 0;
        for (String s : lines) if (s != null && !s.trim().isEmpty()) count++;

        if (count == 2) {
            // Xác định TD3 hay TD2
            String l1 = lines[0].trim();
            if (l1.length() > 36) return parseTD3(lines[0], lines[1]);
            return parseTD2(lines[0], lines[1]);
        } else if (count == 3) {
            return parseTD1(lines[0], lines[1], lines[2]);
        }

        return null;
    }
}
