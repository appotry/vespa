// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "dfa_string_comparator.h"
#include <vespa/vespalib/datastore/atomic_entry_ref.h>
#include <vespa/vespalib/fuzzy/levenshtein_dfa.h>

namespace search::attribute {

/**
 * Class that uses a LevenshteinDfa to fuzzy match a target word against words in a dictionary.
 *
 * The dictionary iterator is advanced based on the successor string from the DFA
 * each time the candidate word is _not_ a match.
 */
class DfaFuzzyMatcher {
private:
    vespalib::fuzzy::LevenshteinDfa _dfa;
    std::vector<uint32_t>           _successor;
    std::vector<uint32_t>           _prefix;
    std::vector<uint32_t>           _successor_suffix;
    uint32_t                        _prefix_size;

    const char*skip_prefix(const char* word) const;
public:
    DfaFuzzyMatcher(std::string_view target, uint8_t max_edits, uint32_t prefix_size, bool cased, vespalib::fuzzy::LevenshteinDfa::DfaType dfa_type);
    ~DfaFuzzyMatcher();

    template <typename DictionaryConstIteratorType>
    bool is_match(const char* word, DictionaryConstIteratorType& itr, const DfaStringComparator::DataStoreType& data_store) {
        if (_prefix_size > 0) {
            word = skip_prefix(word);
            auto match = _dfa.match(word, _successor_suffix);
            if (match.matches()) {
                return true;
            }
            _successor.resize(_prefix.size());
            _successor.insert(_successor.end(), _successor_suffix.begin(), _successor_suffix.end());
        } else {
            auto match = _dfa.match(word, _successor);
            if (match.matches()) {
                return true;
            }
        }
        DfaStringComparator cmp(data_store, _successor);
        itr.seek(vespalib::datastore::AtomicEntryRef(), cmp);
        return false;
    }
};

}
