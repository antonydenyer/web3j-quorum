/*
 * Copyright 2019 Web3 Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.web3j.quorum.tx

import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.quorum.PrivacyFlag
import org.web3j.quorum.Quorum
import org.web3j.quorum.enclave.Enclave
import org.web3j.quorum.enclave.SendResponse
import org.web3j.quorum.tx.util.decode
import org.web3j.quorum.tx.util.encode
import org.web3j.rlp.RlpDecoder
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpList
import org.web3j.rlp.RlpString
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.utils.Numeric

open class QuorumTransactionManager(
    private val web3j: Quorum,
    private val enclave: Enclave,
    private val credentials: Credentials,
    private val publicKey: String,
    private var privateFor: List<String> = listOf(),
    // null privacy flag means that the privacyFlag field would not be serialized which causes the flag to default to StandardPrivate in quorum
    private var PrivacyFlag: PrivacyFlag?,
    // null mandatory for means that the mandatoryFor field would not be serialized which causes the flag to default to nil in quorum
    private var mandatoryFor: List<String>?,
    chainId: Long = -1L,
    attempts: Int = TransactionManager.DEFAULT_POLLING_ATTEMPTS_PER_TX_HASH,
    sleepDuration: Long = TransactionManager.DEFAULT_POLLING_FREQUENCY
) : RawTransactionManager(web3j, credentials, chainId, attempts, sleepDuration) {

    // add extra constructor as java does not have optional parameters
    constructor(
        web3j: Quorum,
        enclave: Enclave,
        credentials: Credentials,
        publicKey: String,
        privateFor: List<String>
    ) : this(web3j, enclave, credentials, publicKey, privateFor, null, null) {
    }

    constructor(
        web3j: Quorum,
        enclave: Enclave,
        credentials: Credentials,
        publicKey: String,
        privateFor: List<String>,
        PrivacyFlag: PrivacyFlag
    ) : this(web3j, enclave, credentials, publicKey, privateFor, PrivacyFlag, null) {
    }

    constructor(
        web3j: Quorum,
        enclave: Enclave,
        credentials: Credentials,
        publicKey: String,
        privateFor: List<String>,
        PrivacyFlag: PrivacyFlag,
        mandatoryFor: List<String>
    ) : this(web3j, enclave, credentials, publicKey, privateFor, PrivacyFlag, mandatoryFor, -1) {
    }

    fun storeRawRequest(payload: String, from: String, to: List<String>): SendResponse {
        val payloadBase64 = encode(Numeric.hexStringToByteArray(payload))
        return enclave.storeRawRequest(payloadBase64, from, to)
    }

    fun sendRaw(signedTx: String, to: List<String>): EthSendTransaction {
        return enclave.sendRawRequest(signedTx, to, PrivacyFlag, mandatoryFor)
    }

    override fun sign(rawTransaction: RawTransaction): String {
        var signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials)
        if (privateFor.isNotEmpty()) {
            signedMessage = setPrivate(signedMessage)
        }
        return Numeric.toHexString(signedMessage)
    }

    override fun signAndSend(rawTransaction: RawTransaction): EthSendTransaction {
        val signedMessage: ByteArray
        if (privateFor.isNotEmpty()) {
            val base64Encoded = encode(Numeric.hexStringToByteArray(rawTransaction.data))
            val response = enclave.storeRawRequest(base64Encoded, publicKey, privateFor)
            val responseDecoded = Numeric.toHexString(decode(response.key))

            val privateTransaction = RawTransaction.createTransaction(
                rawTransaction.nonce, rawTransaction.gasPrice,
                rawTransaction.gasLimit, rawTransaction.to,
                rawTransaction.value, responseDecoded
            )

            val privateMessage = TransactionEncoder.signMessage(privateTransaction, credentials)

            signedMessage = setPrivate(privateMessage)
        } else {
            signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials)
        }
        val hexValue = Numeric.toHexString(signedMessage)
        return enclave.sendRawRequest(hexValue, privateFor, PrivacyFlag, mandatoryFor)
    }

    // If the byte array RLP decodes to a list of size >= 1 containing a list of size >= 3
    // then find the 3rd element from the last. If the element is a RlpString of size 1 then
    // it should be the V component from the SignatureData structure -> mark the transaction as private.
    // If any of of the above checks fails then return the original byte array.
    private fun setPrivate(message: ByteArray): ByteArray {
        var result = message
        val rlpWrappingList = RlpDecoder.decode(message)
        if (rlpWrappingList is RlpList) {
            if (!rlpWrappingList.values.isEmpty()) {
                val rlpList = rlpWrappingList.values[0]
                if (rlpList is RlpList) {
                    val rlpListSize = rlpList.values.size
                    if (rlpListSize > 3) {
                        val vField = rlpList.values[rlpListSize - 3]
                        if (vField is RlpString) {
                            if (1 == vField.bytes.size) {
                                when (vField.bytes[0]) {
                                    28.toByte() -> vField.bytes[0] = 38
                                    else -> vField.bytes[0] = 37
                                }
                                result = RlpEncoder.encode(rlpList)
                            }
                        }
                    }
                }
            }
        }
        return result
    }
}
