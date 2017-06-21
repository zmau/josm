// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io.nmea;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.gpx.GpxConstants;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.gpx.ImmutableGpxTrack;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.tools.JosmRuntimeException;
import org.openstreetmap.josm.tools.date.DateUtils;

/**
 * Reads a NMEA 0183 file. Based on information from
 * <a href="http://www.catb.org/gpsd/NMEA.html">http://www.catb.org/gpsd</a>.
 *
 * NMEA files are in printable ASCII form and may include information such as position,
 * speed, depth, frequency allocation, etc.
 * Typical messages might be 11 to a maximum of 79 characters in length.
 *
 * NMEA standard aims to support one-way serial data transmission from a single "talker"
 * to one or more "listeners". The type of talker is identified by a 2-character mnemonic.
 *
 * NMEA information is encoded through a list of "sentences".
 *
 * @author cbrill
 */
public class NmeaReader {

    public enum VTG {
        COURSE(1), COURSE_REF(2), // true course
        COURSE_M(3), COURSE_M_REF(4), // magnetic course
        SPEED_KN(5), SPEED_KN_UNIT(6), // speed in knots
        SPEED_KMH(7), SPEED_KMH_UNIT(8), // speed in km/h
        REST(9); // version-specific rest

        public final int position;

        VTG(int position) {
            this.position = position;
        }
    }

    public enum RMC {
        TIME(1),
        /** Warning from the receiver (A = data ok, V = warning) */
        RECEIVER_WARNING(2),
        WIDTH_NORTH(3), WIDTH_NORTH_NAME(4), // Latitude, NS
        LENGTH_EAST(5), LENGTH_EAST_NAME(6), // Longitude, EW
        SPEED(7), COURSE(8), DATE(9),           // Speed in knots
        MAGNETIC_DECLINATION(10), UNKNOWN(11),  // magnetic declination
        /**
         * Mode (A = autonom; D = differential; E = estimated; N = not valid; S = simulated)
         *
         * @since NMEA 2.3
         */
        MODE(12);

        public final int position;

        RMC(int position) {
            this.position = position;
        }
    }

    public enum GGA {
        TIME(1), LATITUDE(2), LATITUDE_NAME(3), LONGITUDE(4), LONGITUDE_NAME(5),
        /**
         * Quality (0 = invalid, 1 = GPS, 2 = DGPS, 6 = estimanted (@since NMEA 2.3))
         */
        QUALITY(6), SATELLITE_COUNT(7),
        HDOP(8), // HDOP (horizontal dilution of precision)
        HEIGHT(9), HEIGHT_UNTIS(10), // height above NN (above geoid)
        HEIGHT_2(11), HEIGHT_2_UNTIS(12), // height geoid - height ellipsoid (WGS84)
        GPS_AGE(13), // Age of differential GPS data
        REF(14); // REF station

        public final int position;
        GGA(int position) {
            this.position = position;
        }
    }

    public enum GSA {
        AUTOMATIC(1),
        FIX_TYPE(2), // 1 = not fixed, 2 = 2D fixed, 3 = 3D fixed)
        // PRN numbers for max 12 satellites
        PRN_1(3), PRN_2(4), PRN_3(5), PRN_4(6), PRN_5(7), PRN_6(8),
        PRN_7(9), PRN_8(10), PRN_9(11), PRN_10(12), PRN_11(13), PRN_12(14),
        PDOP(15),   // PDOP (precision)
        HDOP(16),   // HDOP (horizontal precision)
        VDOP(17);   // VDOP (vertical precision)

        public final int position;
        GSA(int position) {
            this.position = position;
        }
    }

    public GpxData data;

    private final SimpleDateFormat rmcTimeFmt = new SimpleDateFormat("ddMMyyHHmmss.SSS");
    private final SimpleDateFormat rmcTimeFmtStd = new SimpleDateFormat("ddMMyyHHmmss");

    private Date readTime(String p) {
        Date d = Optional.ofNullable(rmcTimeFmt.parse(p, new ParsePosition(0)))
                .orElseGet(() -> rmcTimeFmtStd.parse(p, new ParsePosition(0)));
        if (d == null)
            throw new JosmRuntimeException("Date is malformed");
        return d;
    }

    // functons for reading the error stats
    public NMEAParserState ps;

    public int getParserUnknown() {
        return ps.unknown;
    }

    public int getParserZeroCoordinates() {
        return ps.zeroCoord;
    }

    public int getParserChecksumErrors() {
        return ps.checksumErrors+ps.noChecksum;
    }

    public int getParserMalformed() {
        return ps.malformed;
    }

    public int getNumberOfCoordinates() {
        return ps.success;
    }

    public NmeaReader(InputStream source) throws IOException {
        rmcTimeFmt.setTimeZone(DateUtils.UTC);
        rmcTimeFmtStd.setTimeZone(DateUtils.UTC);

        // create the data tree
        data = new GpxData();
        Collection<Collection<WayPoint>> currentTrack = new ArrayList<>();

        try (BufferedReader rd = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(1024);
            int loopstartChar = rd.read();
            ps = new NMEAParserState();
            if (loopstartChar == -1)
                //TODO tell user about the problem?
                return;
            sb.append((char) loopstartChar);
            ps.pDate = "010100"; // TODO date problem
            while (true) {
                // don't load unparsable files completely to memory
                if (sb.length() >= 1020) {
                    sb.delete(0, sb.length()-1);
                }
                int c = rd.read();
                if (c == '$') {
                    parseNMEASentence(sb.toString(), ps);
                    sb.delete(0, sb.length());
                    sb.append('$');
                } else if (c == -1) {
                    // EOF: add last WayPoint if it works out
                    parseNMEASentence(sb.toString(), ps);
                    break;
                } else {
                    sb.append((char) c);
                }
            }
            currentTrack.add(ps.waypoints);
            data.tracks.add(new ImmutableGpxTrack(currentTrack, Collections.<String, Object>emptyMap()));

        } catch (IllegalDataException e) {
            Main.warn(e);
        }
    }

    private static class NMEAParserState {
        protected Collection<WayPoint> waypoints = new ArrayList<>();
        protected String pTime;
        protected String pDate;
        protected WayPoint pWp;

        protected int success; // number of successfully parsed sentences
        protected int malformed;
        protected int checksumErrors;
        protected int noChecksum;
        protected int unknown;
        protected int zeroCoord;
    }

    /**
     * Determines if the given address denotes the given NMEA sentence formatter of a known talker.
     * @param address first tag of an NMEA sentence
     * @param formatter sentence formatter mnemonic code
     * @return {@code true} if the {@code address} denotes the given NMEA sentence formatter of a known talker
     */
    static boolean isSentence(String address, Sentence formatter) {
        for (TalkerId talker : TalkerId.values()) {
            if (address.equals('$' + talker.name() + formatter.name())) {
                return true;
            }
        }
        return false;
    }

    // Parses split up sentences into WayPoints which are stored
    // in the collection in the NMEAParserState object.
    // Returns true if the input made sense, false otherwise.
    private boolean parseNMEASentence(String s, NMEAParserState ps) throws IllegalDataException {
        try {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("s is empty");
            }

            // checksum check:
            // the bytes between the $ and the * are xored
            // if there is no * or other meanities it will throw
            // and result in a malformed packet.
            String[] chkstrings = s.split("\\*");
            if (chkstrings.length > 1) {
                byte[] chb = chkstrings[0].getBytes(StandardCharsets.UTF_8);
                int chk = 0;
                for (int i = 1; i < chb.length; i++) {
                    chk ^= chb[i];
                }
                if (Integer.parseInt(chkstrings[1].substring(0, 2), 16) != chk) {
                    ps.checksumErrors++;
                    ps.pWp = null;
                    return false;
                }
            } else {
                ps.noChecksum++;
            }
            // now for the content
            String[] e = chkstrings[0].split(",");
            String accu;

            WayPoint currentwp = ps.pWp;
            String currentDate = ps.pDate;

            // handle the packet content
            if (isSentence(e[0], Sentence.GGA)) {
                // Position
                LatLon latLon = parseLatLon(
                        e[GGA.LATITUDE_NAME.position],
                        e[GGA.LONGITUDE_NAME.position],
                        e[GGA.LATITUDE.position],
                        e[GGA.LONGITUDE.position]
                );
                if (latLon == null) {
                    throw new IllegalDataException("Malformed lat/lon");
                }

                if (LatLon.ZERO.equals(latLon)) {
                    ps.zeroCoord++;
                    return false;
                }

                // time
                accu = e[GGA.TIME.position];
                Date d = readTime(currentDate+accu);

                if ((ps.pTime == null) || (currentwp == null) || !ps.pTime.equals(accu)) {
                    // this node is newer than the previous, create a new waypoint.
                    // no matter if previous WayPoint was null, we got something better now.
                    ps.pTime = accu;
                    currentwp = new WayPoint(latLon);
                }
                if (!currentwp.attr.containsKey("time")) {
                    // As this sentence has no complete time only use it
                    // if there is no time so far
                    currentwp.setTime(d);
                }
                // elevation
                accu = e[GGA.HEIGHT_UNTIS.position];
                if ("M".equals(accu)) {
                    // Ignore heights that are not in meters for now
                    accu = e[GGA.HEIGHT.position];
                    if (!accu.isEmpty()) {
                        Double.parseDouble(accu);
                        // if it throws it's malformed; this should only happen if the
                        // device sends nonstandard data.
                        if (!accu.isEmpty()) { // FIX ? same check
                            currentwp.put(GpxConstants.PT_ELE, accu);
                        }
                    }
                }
                // number of satellites
                accu = e[GGA.SATELLITE_COUNT.position];
                int sat = 0;
                if (!accu.isEmpty()) {
                    sat = Integer.parseInt(accu);
                    currentwp.put(GpxConstants.PT_SAT, accu);
                }
                // h-dilution
                accu = e[GGA.HDOP.position];
                if (!accu.isEmpty()) {
                    currentwp.put(GpxConstants.PT_HDOP, Float.valueOf(accu));
                }
                // fix
                accu = e[GGA.QUALITY.position];
                if (!accu.isEmpty()) {
                    int fixtype = Integer.parseInt(accu);
                    switch(fixtype) {
                    case 0:
                        currentwp.put(GpxConstants.PT_FIX, "none");
                        break;
                    case 1:
                        if (sat < 4) {
                            currentwp.put(GpxConstants.PT_FIX, "2d");
                        } else {
                            currentwp.put(GpxConstants.PT_FIX, "3d");
                        }
                        break;
                    case 2:
                        currentwp.put(GpxConstants.PT_FIX, "dgps");
                        break;
                    default:
                        break;
                    }
                }
            } else if (isSentence(e[0], Sentence.VTG)) {
                // COURSE
                accu = e[VTG.COURSE_REF.position];
                if ("T".equals(accu)) {
                    // other values than (T)rue are ignored
                    accu = e[VTG.COURSE.position];
                    if (!accu.isEmpty()) {
                        Double.parseDouble(accu);
                        currentwp.put("course", accu);
                    }
                }
                // SPEED
                accu = e[VTG.SPEED_KMH_UNIT.position];
                if (accu.startsWith("K")) {
                    accu = e[VTG.SPEED_KMH.position];
                    if (!accu.isEmpty()) {
                        double speed = Double.parseDouble(accu);
                        speed /= 3.6; // speed in m/s
                        currentwp.put("speed", Double.toString(speed));
                    }
                }
            } else if (isSentence(e[0], Sentence.GSA)) {
                // vdop
                accu = e[GSA.VDOP.position];
                if (!accu.isEmpty() && currentwp != null) {
                    currentwp.put(GpxConstants.PT_VDOP, Float.valueOf(accu));
                }
                // hdop
                accu = e[GSA.HDOP.position];
                if (!accu.isEmpty() && currentwp != null) {
                    currentwp.put(GpxConstants.PT_HDOP, Float.valueOf(accu));
                }
                // pdop
                accu = e[GSA.PDOP.position];
                if (!accu.isEmpty() && currentwp != null) {
                    currentwp.put(GpxConstants.PT_PDOP, Float.valueOf(accu));
                }
            } else if (isSentence(e[0], Sentence.RMC)) {
                // coordinates
                LatLon latLon = parseLatLon(
                        e[RMC.WIDTH_NORTH_NAME.position],
                        e[RMC.LENGTH_EAST_NAME.position],
                        e[RMC.WIDTH_NORTH.position],
                        e[RMC.LENGTH_EAST.position]
                );
                if (LatLon.ZERO.equals(latLon)) {
                    ps.zeroCoord++;
                    return false;
                }
                // time
                currentDate = e[RMC.DATE.position];
                String time = e[RMC.TIME.position];

                Date d = readTime(currentDate+time);

                if (ps.pTime == null || currentwp == null || !ps.pTime.equals(time)) {
                    // this node is newer than the previous, create a new waypoint.
                    ps.pTime = time;
                    currentwp = new WayPoint(latLon);
                }
                // time: this sentence has complete time so always use it.
                currentwp.setTime(d);
                // speed
                accu = e[RMC.SPEED.position];
                if (!accu.isEmpty() && !currentwp.attr.containsKey("speed")) {
                    double speed = Double.parseDouble(accu);
                    speed *= 0.514444444; // to m/s
                    currentwp.put("speed", Double.toString(speed));
                }
                // course
                accu = e[RMC.COURSE.position];
                if (!accu.isEmpty() && !currentwp.attr.containsKey("course")) {
                    Double.parseDouble(accu);
                    currentwp.put("course", accu);
                }

                // TODO fix?
                // * Mode (A = autonom; D = differential; E = estimated; N = not valid; S
                // * = simulated)
                // *
                // * @since NMEA 2.3
                //
                //MODE(12);
            } else {
                ps.unknown++;
                return false;
            }
            ps.pDate = currentDate;
            if (ps.pWp != currentwp) {
                if (ps.pWp != null) {
                    ps.pWp.setTime();
                }
                ps.pWp = currentwp;
                ps.waypoints.add(currentwp);
                ps.success++;
                return true;
            }
            return true;

        } catch (IllegalArgumentException | IndexOutOfBoundsException | IllegalDataException ex) {
            // out of bounds and such
            Main.debug(ex);
            ps.malformed++;
            ps.pWp = null;
            return false;
        }
    }

    private static LatLon parseLatLon(String ns, String ew, String dlat, String dlon) {
        String widthNorth = dlat.trim();
        String lengthEast = dlon.trim();

        // return a zero latlon instead of null so it is logged as zero coordinate
        // instead of malformed sentence
        if (widthNorth.isEmpty() && lengthEast.isEmpty()) return LatLon.ZERO;

        // The format is xxDDLL.LLLL
        // xx optional whitespace
        // DD (int) degres
        // LL.LLLL (double) latidude
        int latdegsep = widthNorth.indexOf('.') - 2;
        if (latdegsep < 0) return null;

        int latdeg = Integer.parseInt(widthNorth.substring(0, latdegsep));
        double latmin = Double.parseDouble(widthNorth.substring(latdegsep));
        if (latdeg < 0) {
            latmin *= -1.0;
        }
        double lat = latdeg + latmin / 60;
        if ("S".equals(ns)) {
            lat = -lat;
        }

        int londegsep = lengthEast.indexOf('.') - 2;
        if (londegsep < 0) return null;

        int londeg = Integer.parseInt(lengthEast.substring(0, londegsep));
        double lonmin = Double.parseDouble(lengthEast.substring(londegsep));
        if (londeg < 0) {
            lonmin *= -1.0;
        }
        double lon = londeg + lonmin / 60;
        if ("W".equals(ew)) {
            lon = -lon;
        }
        return new LatLon(lat, lon);
    }
}