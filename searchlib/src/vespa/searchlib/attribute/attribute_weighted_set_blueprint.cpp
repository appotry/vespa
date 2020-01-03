// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "attribute_weighted_set_blueprint.h"
#include <vespa/searchcommon/attribute/i_search_context.h>
#include <vespa/searchlib/common/bitvector.h>
#include <vespa/searchlib/fef/matchdatalayout.h>
#include <vespa/searchlib/query/query_term_ucs4.h>
#include <vespa/searchlib/queryeval/weighted_set_term_search.h>
#include <vespa/vespalib/objects/visit.h>
#include <vespa/vespalib/stllike/hash_map.h>
#include <vespa/vespalib/util/stringfmt.h>

namespace search {

namespace {

using attribute::ISearchContext;
using attribute::IAttributeVector;
//-----------------------------------------------------------------------------

class UseAttr
{
private:
    const attribute::IAttributeVector &_attr;

protected:
    const attribute::IAttributeVector &attribute() const { return _attr; }

public:
    UseAttr(const attribute::IAttributeVector & attr)
        : _attr(attr) {}
};

//-----------------------------------------------------------------------------

class UseStringEnum : public UseAttr
{
public:
    UseStringEnum(const IAttributeVector & attr)
        : UseAttr(attr) {}
    auto mapToken(const ISearchContext &context) const {
        return attribute().findFoldedEnums(context.queryTerm()->getTerm());
    }
    int64_t getToken(uint32_t docId) const {
        return attribute().getEnum(docId);
    }
};

//-----------------------------------------------------------------------------

class UseInteger : public UseAttr
{
public:
    UseInteger(const IAttributeVector & attr) : UseAttr(attr) {}
    std::vector<int64_t> mapToken(const ISearchContext &context) const {
        std::vector<int64_t> result;
        Int64Range range(context.getAsIntegerTerm());
        if (range.isPoint()) {
            result.push_back(range.lower());
        }
        return result;
    }
    int64_t getToken(uint32_t docId) const {
        return attribute().getInt(docId);
    }
};

//-----------------------------------------------------------------------------

template <typename T>
class AttributeFilter final : public queryeval::SearchIterator
{
private:
    typedef vespalib::hash_map<int64_t, int32_t> Map;
    typedef fef::TermFieldMatchData TFMD;

    TFMD    &_tfmd;
    T        _attr;
    Map      _map;
    int32_t  _weight;

public:
    AttributeFilter(fef::TermFieldMatchData &tfmd,
                    const IAttributeVector & attr,
                    const std::vector<int32_t> & weights,
                    const std::vector<ISearchContext*> & contexts)
        : _tfmd(tfmd), _attr(attr), _map(), _weight(0)
    {
        for (size_t i = 0; i < contexts.size(); ++i) {
            for (int64_t token : _attr.mapToken(*contexts[i])) {
                _map[token] = weights[i];
            }
        }
    }
    void and_hits_into(BitVector & result,uint32_t begin_id) override {
        Map::iterator end = _map.end();
        result.foreach_truebit([&, end](uint32_t key) { if ( _map.find(_attr.getToken(key)) == end) { result.clearBit(key); }}, begin_id);
    }

    void doSeek(uint32_t docId) override {
        Map::const_iterator pos = _map.find(_attr.getToken(docId));
        if (pos != _map.end()) {
            _weight = pos->second;
            setDocId(docId);
        }
    }
    void doUnpack(uint32_t docId) override {
        _tfmd.reset(docId);
        fef::TermFieldMatchDataPosition pos;
        pos.setElementWeight(_weight);
        _tfmd.appendPosition(pos);
    }
    void visitMembers(vespalib::ObjectVisitor &) const override {}
};

//-----------------------------------------------------------------------------

} // namespace search::<unnamed>

AttributeWeightedSetBlueprint::AttributeWeightedSetBlueprint(const queryeval::FieldSpec &field, const IAttributeVector & attr)
    : queryeval::ComplexLeafBlueprint(field),
      _numDocs(attr.getNumDocs()),
      _estHits(0),
      _weights(),
      _attr(attr),
      _contexts()
{
    set_allow_termwise_eval(true);
}

AttributeWeightedSetBlueprint::~AttributeWeightedSetBlueprint()
{
    while (!_contexts.empty()) {
        delete _contexts.back();
        _contexts.pop_back();
    }
}

void
AttributeWeightedSetBlueprint::addToken(std::unique_ptr<ISearchContext> context, int32_t weight)
{
    _estHits = std::min(_estHits + context->approximateHits(), _numDocs);
    setEstimate(HitEstimate(_estHits, (_estHits == 0)));
    _weights.push_back(weight);
    _contexts.push_back(context.get());
    context.release();
}

queryeval::SearchIterator::UP
AttributeWeightedSetBlueprint::createLeafSearch(const fef::TermFieldMatchDataArray &tfmda, bool strict) const
{
    assert(tfmda.size() == 1);
    fef::TermFieldMatchData &tfmd = *tfmda[0];
    if (strict) { // use generic weighted set search
        fef::MatchDataLayout layout;
        auto handle = layout.allocTermField(tfmd.getFieldId());
        auto match_data = layout.createMatchData();
        auto child_tfmd = match_data->resolveTermField(handle);
        std::vector<queryeval::SearchIterator*> children(_contexts.size());
        for (size_t i = 0; i < _contexts.size(); ++i) {
            children[i] = _contexts[i]->createIterator(child_tfmd, true).release();
        }
        return queryeval::SearchIterator::UP(queryeval::WeightedSetTermSearch::create(children, tfmd, _weights, std::move(match_data)));
    } else { // use attribute filter optimization
        bool isSingleValue = !_attr.hasMultiValue();
        bool isString = (_attr.isStringType() && _attr.hasEnum());
        bool isInteger = _attr.isIntegerType();
        assert(isSingleValue);
        (void) isSingleValue;
        if (isString) {
            return std::make_unique<AttributeFilter<UseStringEnum>>(tfmd, _attr, _weights, _contexts);
        } else {
            assert(isInteger);
            (void) isInteger;
            return std::make_unique<AttributeFilter<UseInteger>>(tfmd, _attr, _weights, _contexts);
        }
    }
}

void
AttributeWeightedSetBlueprint::fetchPostings(const queryeval::ExecuteInfo &execInfo)
{
    if (execInfo.isStrict()) {
        for (auto * context : _contexts) {
            context->fetchPostings(execInfo);
        }
    }
}

void
AttributeWeightedSetBlueprint::visitMembers(vespalib::ObjectVisitor &visitor) const
{
    ComplexLeafBlueprint::visitMembers(visitor);
    visitor.visitString("attribute", _attr.getName());
    visitor.openStruct("terms", "TermList");
    for (size_t i = 0; i < _contexts.size(); ++i) {
        const ISearchContext * context = _contexts[i];
        visitor.openStruct(vespalib::make_string("[%zu]", i), "Term");
        visitor.visitBool("valid", context->valid());
        if (context-> valid()) {
            bool isString = (_attr.isStringType() && _attr.hasEnum());
            if (isString) {
                visitor.visitString("term", context->queryTerm()->getTerm());
            } else {
                visitor.visitInt("term", context->getAsIntegerTerm().lower());
            }
            visitor.visitInt("weight", _weights[i]);
        }
        visitor.closeStruct();
    }
    visitor.closeStruct();
}

} // namespace search
