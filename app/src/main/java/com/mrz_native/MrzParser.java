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
        if (l1.length() < 44 || l2.length() < 44) return null;

        try {
            String documentType = l1.substring(0, 2).trim();
            String issuingCountry = l1.substring(2, 5);
            String[] names = l1.substring(5).split("<<");
            String surname = names.length > 0 ? names[0].replace("<", " ") : "";
            String givenNames = names.length > 1 ? names[1].replace("<", " ").trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            String documentNumber = l2.substring(0, 9).replace("<", "");
            String nationality = l2.substring(10, 13);
            String dob = l2.substring(13, 19);
            String gender = l2.substring(20, 21);
            String expiryDate = l2.substring(21, 27);
            String personalNumber = l2.substring(28, 42).replace("<", "");

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
        if (l1.length() < 36 || l2.length() < 36) return null;

        try {
            String documentType = l1.substring(0, 2).trim();
            String issuingCountry = l1.substring(2, 5);
            String[] names = l1.substring(5).split("<<");
            String surname = names.length > 0 ? names[0].replace("<", " ") : "";
            String givenNames = names.length > 1 ? names[1].replace("<", " ").trim() : "";
            String fullName = (surname + " " + givenNames).trim();

            String documentNumber = l2.substring(0, 9).replace("<", "");
            String nationality = l2.substring(10, 13);
            String dob = l2.substring(13, 19);
            String gender = l2.substring(20, 21);
            String expiryDate = l2.substring(21, 27);
            String personalNumber = l2.substring(28, 35).replace("<", "");

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
        if (l1.length() < 30 || l2.length() < 30 || l3.length() < 30) return null;

        try {
            String documentType = l1.substring(0, 2).trim();
            String issuingCountry = l1.substring(2, 5);
            String documentNumber = l1.substring(5, 14).replace("<", "");

            String dob = l2.substring(0, 6);
            String gender = l2.substring(7, 8);
            String expiryDate = l2.substring(8, 14);
            String nationality = l2.substring(15, 18);
            String personalNumber = l2.substring(18).replace("<", "");

            String[] names = l3.split("<<");
            String surname = names.length > 0 ? names[0].replace("<", " ") : "";
            String givenNames = names.length > 1 ? names[1].replace("<", " ").trim() : "";
            String fullName = (surname + " " + givenNames).trim();

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
