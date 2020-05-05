/*
 * Copyright 2014-2018 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.status;

import org.agrona.BitUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.CountersReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Counter used to store the status of a bind address and port for the local end of a channel.
 * <p>
 * When the value is {@link ChannelEndpointStatus#ACTIVE} then the key value and label will be updated with the
 * socket address and port which is bound.
 */
public class LocalSocketAddressStatus
{
    private static final int CHANNEL_STATUS_ID_OFFSET = 0;
    private static final int LOCAL_SOCKET_ADDRESS_LENGTH_OFFSET = CHANNEL_STATUS_ID_OFFSET + BitUtil.SIZE_OF_INT;
    private static final int LOCAL_SOCKET_ADDRESS_STRING_OFFSET =
        LOCAL_SOCKET_ADDRESS_LENGTH_OFFSET + BitUtil.SIZE_OF_INT;

    private static final int MAX_IPV6_LENGTH = "[ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255]:65536".length();

    /**
     * Initial length length for a key, this will be expanded later when bound.
     */
    public static final int INITIAL_LENGTH = BitUtil.SIZE_OF_INT * 2;

    /**
     * Type of the counter used to track a local socket address and port.
     */
    public static final int LOCAL_SOCKET_ADDRESS_STATUS_TYPE_ID = 14;

    /**
     * Allocate a counter to represent a local socket address associated with a channel.
     *
     * @param tempBuffer      for building up the key and label.
     * @param countersManager which will allocate the counter.
     * @param channelStatusId with which the new counter is associated.
     * @param name            for the counter to put in the label.
     * @param typeId          to categorise the counter.
     * @return the allocated counter.
     */
    public static AtomicCounter allocate(
        final MutableDirectBuffer tempBuffer,
        final CountersManager countersManager,
        final int channelStatusId,
        final String name,
        final int typeId)
    {
        tempBuffer.putInt(0, channelStatusId);
        tempBuffer.putInt(LOCAL_SOCKET_ADDRESS_LENGTH_OFFSET, 0);

        final int keyLength = INITIAL_LENGTH;

        int labelLength = 0;
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, name);
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, ": ");
        labelLength += tempBuffer.putIntAscii(keyLength + labelLength, channelStatusId);
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, " ");

        return countersManager.newCounter(typeId, tempBuffer, 0, keyLength, tempBuffer, keyLength, labelLength);
    }

    /**
     * Update the key metadata and label to contain the bound socket address once the transport is active.
     *
     * @param counter                representing the local socket address of the transport.
     * @param bindAddressAndPort     in string representation.
     * @param countersMetadataBuffer to be updated for the bound address.
     */
    public static void updateBindAddress(
        final AtomicCounter counter, final String bindAddressAndPort, final UnsafeBuffer countersMetadataBuffer)
    {
        if (bindAddressAndPort.length() > MAX_IPV6_LENGTH)
        {
            throw new IllegalArgumentException(
                "bindAddressAndPort value too long: " + bindAddressAndPort.length() + " max: " + MAX_IPV6_LENGTH);
        }

        // TODO: Use this once Agrona is updated to 1.5
//        counter.updateKey((keyBuffer) ->
//        {
//            final int bindingLength = keyBuffer.putStringWithoutLengthAscii(
//                BIND_ADDRESS_AND_PORT_STRING_OFFSET, bindAddressAndPort);
//            keyBuffer.putInt(BIND_ADDRESS_AND_PORT_OFFSET, bindingLength);
//        });

        // TODO: Remove this bit when Agrona is updated to 1.5
        final int keyIndex = CountersReader.metaDataOffset(counter.id()) + CountersReader.KEY_OFFSET;
        final int addressStringIndex = keyIndex + LOCAL_SOCKET_ADDRESS_STRING_OFFSET;
        final int length = countersMetadataBuffer.putStringWithoutLengthAscii(addressStringIndex, bindAddressAndPort);
        final int addressLengthIndex = keyIndex + LOCAL_SOCKET_ADDRESS_LENGTH_OFFSET;
        countersMetadataBuffer.putInt(addressLengthIndex, length);

        counter.appendToLabel(bindAddressAndPort);
    }

    /**
     * Find the list of currently bound local sockets.
     *
     * @param countersReader  for the connected driver.
     * @param channelStatus   value for the channel which aggregates the transports.
     * @param channelStatusId identity of the counter for the channel which aggregates the transports.
     * @return the list of active bound local socket addresses.
     */
    public static List<String> findAddresses(
        final CountersReader countersReader, final long channelStatus, final int channelStatusId)
    {
        if (channelStatus != ChannelEndpointStatus.ACTIVE)
        {
            return Collections.emptyList();
        }

        final ArrayList<String> bindings = new ArrayList<>(2);

        countersReader.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                if (LOCAL_SOCKET_ADDRESS_STATUS_TYPE_ID == typeId &&
                    channelStatusId == keyBuffer.getInt(CHANNEL_STATUS_ID_OFFSET) &&
                    ChannelEndpointStatus.ACTIVE == countersReader.getCounterValue(counterId))
                {
                    final int length = keyBuffer.getInt(LOCAL_SOCKET_ADDRESS_LENGTH_OFFSET);
                    if (length > 0)
                    {
                        bindings.add(keyBuffer.getStringWithoutLengthAscii(LOCAL_SOCKET_ADDRESS_STRING_OFFSET, length));
                    }
                }
            });

        return bindings;
    }
}