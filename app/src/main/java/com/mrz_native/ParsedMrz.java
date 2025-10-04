package com.mrz_native;

public class ParsedMrz {
    private String documentType;
    private String issuingCountry;
    private String name;
    private String documentNumber;
    private String nationality;
    private String dateOfBirth; // YYMMDD
    private String gender;
    private String dateOfExpiry; // YYMMDD
    private String personalNumber;

    public ParsedMrz(String documentType, String issuingCountry, String name,
                     String documentNumber, String nationality, String dateOfBirth,
                     String gender, String dateOfExpiry, String personalNumber) {
        this.documentType = documentType;
        this.issuingCountry = issuingCountry;
        this.name = name;
        this.documentNumber = documentNumber;
        this.nationality = nationality;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.dateOfExpiry = dateOfExpiry;
        this.personalNumber = personalNumber;
    }

    // Getters
    public String getDocumentType() { return documentType; }
    public String getIssuingCountry() { return issuingCountry; }
    public String getName() { return name; }
    public String getDocumentNumber() { return documentNumber; }
    public String getNationality() { return nationality; }
    public String getDateOfBirth() { return dateOfBirth; }
    public String getGender() { return gender; }
    public String getDateOfExpiry() { return dateOfExpiry; }
    public String getPersonalNumber() { return personalNumber; }
}
