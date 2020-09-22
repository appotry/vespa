// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "indexenvironment.h"
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/vespalib/util/stringfmt.h>

namespace search::fef::test {

using vespalib::eval::ValueType;

IndexEnvironment::IndexEnvironment() = default;

IndexEnvironment::~IndexEnvironment() = default;

const FieldInfo *
IndexEnvironment::getField(uint32_t id) const
{
    return id < _fields.size() ? &_fields[id] : nullptr;
}

const FieldInfo *
IndexEnvironment::getFieldByName(const string &name) const
{
    for (std::vector<FieldInfo>::const_iterator it = _fields.begin();
         it != _fields.end(); ++it) {
        if (it->name() == name) {
            return &(*it);
        }
    }
    return nullptr;
}


vespalib::eval::ConstantValue::UP
IndexEnvironment::getConstantValue(const vespalib::string &name) const
{
    auto it = _constants.find(name);
    if (it != _constants.end()) {
        return std::make_unique<ConstantRef>(it->second);
    } else {
        return vespalib::eval::ConstantValue::UP(nullptr);
    }
}

void
IndexEnvironment::addConstantValue(const vespalib::string &name,
                                   vespalib::eval::ValueType type,
                                   std::unique_ptr<vespalib::eval::Value> value)
{
    auto insertRes = _constants.emplace(name,
                                        Constant(std::move(type),
                                                 std::move(value)));
    assert(insertRes.second); // successful insert
    (void) insertRes;
}

const OnnxModel *
IndexEnvironment::getOnnxModel(const vespalib::string &name) const
{
    auto pos = _models.find(name);
    if (pos != _models.end()) {
        return &pos->second;
    }
    return nullptr;
}

void
IndexEnvironment::addOnnxModel(const OnnxModel &model)
{
    _models.insert_or_assign(model.name(), model);
}


}
