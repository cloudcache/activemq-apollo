/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.wireformat;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;

import org.apache.activemq.transport.Transport;
import org.apache.activemq.util.ByteArrayInputStream;
import org.apache.activemq.util.ByteArrayOutputStream;
import org.apache.activemq.util.ByteSequence;
import org.apache.activemq.util.FactoryFinder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class MultiWireFormatFactory implements WireFormatFactory {

    private static final Log LOG = LogFactory.getLog(MultiWireFormatFactory.class);

    private static final FactoryFinder WIREFORMAT_FACTORY_FINDER = new FactoryFinder("META-INF/services/org/apache/activemq/wireformat/");

    private String wireFormats;
    private ArrayList<WireFormatFactory> wireFormatFactories;

    static class MultiWireFormat implements WireFormat {

        public static final String WIREFORMAT_NAME = "multi";

        ArrayList<WireFormatFactory> wireFormatFactories = new ArrayList<WireFormatFactory>();
        WireFormat wireFormat;
        int maxHeaderLength;

        public int getVersion() {
            return 0;
        }

        public boolean inReceive() {
            return wireFormat.inReceive();
        }

        public void setVersion(int version) {
            wireFormat.setVersion(version);
        }

        private ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private ByteArrayInputStream peeked;

        public Object unmarshal(DataInput in) throws IOException {

            while (wireFormat == null) {

                int readByte = ((InputStream) in).read();
                if (readByte < 0) {
                    throw new EOFException();
                }
                baos.write(readByte);

                // Try to discriminate what we have read so far.
                for (WireFormatFactory wff : wireFormatFactories) {
                    if (wff.matchesWireformatHeader(baos.toByteSequence())) {
                        wireFormat = wff.createWireFormat();
                        break;
                    }
                }

                if (baos.size() >= maxHeaderLength) {
                    throw new IOException("Could not discriminate the protocol.");
                }
            }

            // If we have some peeked data we need to feed that back..  Only happens
            // for the first few bytes of the protocol header.
            if (peeked != null) {
                in = new DataInputStream(new ConcatInputStream(peeked, (InputStream) in));
                Object rc = wireFormat.unmarshal(in);
                if (peeked.available() <= 0) {
                    peeked = null;
                }
                return rc;
            }

            return wireFormat.unmarshal(in);
        }

        public void marshal(Object command, DataOutput out) throws IOException {
            wireFormat.marshal(command, out);
        }

        public ByteSequence marshal(Object command) throws IOException {
            throw new UnsupportedOperationException();
        }

        public Object unmarshal(ByteSequence packet) throws IOException {
            throw new UnsupportedOperationException();
        }

        public ArrayList<WireFormatFactory> getWireFormatFactories() {
            return wireFormatFactories;
        }

        private void setWireFormatFactories(ArrayList<WireFormatFactory> wireFormatFactories) {
            this.wireFormatFactories = wireFormatFactories;
            maxHeaderLength = 0;
            for (WireFormatFactory wff : wireFormatFactories) {
                maxHeaderLength = Math.max(maxHeaderLength, wff.maxWireformatHeaderLength());
            }
        }

        public Transport createTransportFilters(Transport transport, Map options) {
            return transport;
        }

        public String getName() {
            if (wireFormat == null) {
                return WIREFORMAT_NAME;
            } else {
                return wireFormat.getName();
            }
        }
    }

    public WireFormat createWireFormat() {
        MultiWireFormat rc = new MultiWireFormat();
        if (wireFormatFactories == null) {
            wireFormatFactories = new ArrayList<WireFormatFactory>();
            String[] formats = getWireFormats().split("\\,");
            for (int i = 0; i < formats.length; i++) {
                try {
                    WireFormatFactory wff = (WireFormatFactory) WIREFORMAT_FACTORY_FINDER.newInstance(formats[i]);
                    if (wff.isDiscriminatable()) {
                        wireFormatFactories.add(wff);
                    } else {
                        throw new Exception("Not Discriminitable");
                    }
                } catch (Exception e) {
                    LOG.warn("Invalid wireformat '" + formats[i] + "': " + e.getMessage());
                }
            }
        }
        rc.setWireFormatFactories(wireFormatFactories);
        return rc;
    }

    public String getWireFormats() {
        return wireFormats;
    }

    public void setWireFormats(String formats) {
        this.wireFormats = formats;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.activemq.wireformat.WireFormatFactory#isDiscriminatable()
     */
    public boolean isDiscriminatable() {
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.activemq.wireformat.WireFormatFactory#matchesWireformatHeader
     * (org.apache.activemq.util.ByteSequence)
     */
    public boolean matchesWireformatHeader(ByteSequence byteSequence) {
        throw new UnsupportedOperationException();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.activemq.wireformat.WireFormatFactory#maxWireformatHeaderLength
     * ()
     */
    public int maxWireformatHeaderLength() {
        throw new UnsupportedOperationException();
    }


}
